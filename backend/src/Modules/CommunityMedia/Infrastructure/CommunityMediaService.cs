using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.CommunityMedia.Application;
using SightseeingTravel.Modules.CommunityMedia.Domain;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.CommunityMedia.Infrastructure;

public sealed class CommunityMediaService : ICommunityMediaService
{
    private readonly CommunityMediaDbContext _dbContext;
    private readonly IAttractionGeofenceQueryService _geofenceQueryService;
    private readonly IObjectStorageService _objectStorageService;
    private readonly ICurrentUser _currentUser;
    private readonly IDateTimeProvider _dateTimeProvider;

    public CommunityMediaService(
        CommunityMediaDbContext dbContext,
        IAttractionGeofenceQueryService geofenceQueryService,
        IObjectStorageService objectStorageService,
        ICurrentUser currentUser,
        IDateTimeProvider dateTimeProvider)
    {
        _dbContext = dbContext;
        _geofenceQueryService = geofenceQueryService;
        _objectStorageService = objectStorageService;
        _currentUser = currentUser;
        _dateTimeProvider = dateTimeProvider;
    }

    public async Task<Result<PagedResponse<CommunityPhotoDto>>> GetPoiPhotosAsync(
        Guid poiId,
        string sortBy,
        int page,
        int pageSize,
        CancellationToken ct = default)
    {
        var normalizedPage = Math.Max(1, page);
        var normalizedPageSize = Math.Clamp(pageSize <= 0 ? 20 : pageSize, 1, 100);

        var query = _dbContext.CommunityPhotos
            .AsNoTracking()
            .Where(p => p.PoiId == poiId && p.Status == CommunityPhotoStatus.Published);

        var isLatest = string.Equals(sortBy, "latest", StringComparison.OrdinalIgnoreCase);
        query = isLatest
            ? query.OrderByDescending(p => p.CreatedAt)
            : query.OrderByDescending(p => p.LikeCount).ThenByDescending(p => p.CreatedAt);

        var totalCount = await query.CountAsync(ct);
        var photos = await query
            .Skip((normalizedPage - 1) * normalizedPageSize)
            .Take(normalizedPageSize)
            .ToListAsync(ct);

        var likedPhotoIds = await GetLikedPhotoIdsForCurrentUserAsync(photos.Select(p => p.Id), ct);

        var items = photos
            .Select(p => MapToCommunityPhotoDto(p, likedPhotoIds.Contains(p.Id)))
            .ToList();

        return Result.Success(new PagedResponse<CommunityPhotoDto>(
            items,
            normalizedPage,
            normalizedPageSize,
            totalCount));
    }

    public async Task<Result<CommunityPhotoDto>> UploadPhotoAsync(
        UploadPhotoCommand command,
        CancellationToken ct = default)
    {
        if (!GeoCalculator.IsValidCoordinate(command.CapturedLatitude, command.CapturedLongitude))
        {
            return Result.Failure<CommunityPhotoDto>(new Error(
                ErrorCodes.PoiOutOfGeofenceRange,
                "Captured GPS coordinates are invalid.",
                ErrorType.Validation));
        }

        if (command.AzimuthDegrees.HasValue && (command.AzimuthDegrees.Value < 0.0 || command.AzimuthDegrees.Value >= 360.0))
        {
            return Result.Failure<CommunityPhotoDto>(new Error(
                "PHOTO_INVALID_AZIMUTH",
                "Azimuth degrees must be between 0 and 359.99.",
                ErrorType.Validation));
        }

        if (command.PitchDegrees.HasValue && (command.PitchDegrees.Value < -90.0 || command.PitchDegrees.Value > 90.0))
        {
            return Result.Failure<CommunityPhotoDto>(new Error(
                "PHOTO_INVALID_PITCH",
                "Pitch degrees must be between -90 and 90.",
                ErrorType.Validation));
        }

        if (!string.IsNullOrWhiteSpace(command.Caption) && command.Caption.Trim().Length > CommunityPhoto.MaxCaptionLength)
        {
            return Result.Failure<CommunityPhotoDto>(new Error(
                "PHOTO_CAPTION_TOO_LONG",
                $"Caption cannot exceed {CommunityPhoto.MaxCaptionLength} characters.",
                ErrorType.Validation));
        }

        // 1. Check POI exists & is Published
        var poi = await _geofenceQueryService.GetPublishedPoiGeofenceAsync(
            command.PoiId,
            _currentUser.PreferredLanguage,
            ct);

        if (poi is null || !poi.IsPublished)
        {
            return Result.Failure<CommunityPhotoDto>(new Error(
                ErrorCodes.PoiNotFound,
                $"Published attraction POI '{command.PoiId}' was not found.",
                ErrorType.NotFound));
        }

        // 2. Check GPS distance via GeoCalculator.IsWithinGeofence
        var isWithinGeofence = GeoCalculator.IsWithinGeofence(
            command.CapturedLatitude,
            command.CapturedLongitude,
            poi.Latitude,
            poi.Longitude,
            poi.ActivationRadiusMeters,
            out var actualDistanceMeters);

        if (!isWithinGeofence)
        {
            var maxAllowedMeters = poi.ActivationRadiusMeters + GeoCalculator.DefaultGpsToleranceMeters;
            return Result.Failure<CommunityPhotoDto>(new Error(
                ErrorCodes.PoiOutOfGeofenceRange,
                $"You must be on-site at '{poi.LocalizedName}' to upload a community photo. Current distance: {actualDistanceMeters:F1}m (allowed: {maxAllowedMeters:F0}m).",
                ErrorType.UnprocessableEntity));
        }

        // 3. Validate image header & size <= 5MB
        var signatureValidation = ImageSignatureValidator.ValidateAndGetExtension(
            command.FileStream,
            command.FileLength,
            command.ContentType);

        if (signatureValidation.IsFailure)
        {
            return Result.Failure<CommunityPhotoDto>(signatureValidation.Error);
        }

        var extension = signatureValidation.Value;
        var resolvedContentType = extension switch
        {
            ".jpg" => "image/jpeg",
            ".png" => "image/png",
            ".webp" => "image/webp",
            _ => "application/octet-stream"
        };

        // 4. Upload stream to Contabo S3 with key pois/{poiId}/community/{yyyy}/{MM}/{photoId}{ext}
        var now = _dateTimeProvider.UtcNow;
        var photoId = Guid.NewGuid();
        var objectKey = $"pois/{command.PoiId}/community/{now:yyyy}/{now:MM}/{photoId}{extension}";

        StorageUploadResult uploadResult;
        try
        {
            uploadResult = await _objectStorageService.UploadPhotoAsync(
                command.FileStream,
                resolvedContentType,
                objectKey,
                ct);
        }
        catch (ObjectStorageUploadException exception)
        {
            var message = exception.StorageErrorCode == "SignatureDoesNotMatch"
                ? "Contabo Object Storage từ chối chữ ký xác thực. Vui lòng kiểm tra cặp Access Key/Secret Key trong cấu hình máy chủ."
                : "Không thể lưu ảnh vào kho lưu trữ. Vui lòng thử lại sau.";

            return Result.Failure<CommunityPhotoDto>(new Error(
                "PHOTO_STORAGE_UPLOAD_FAILED",
                message,
                ErrorType.Failure));
        }

        // 5. Save CommunityPhoto with Status = Published
        var photo = new CommunityPhoto(
            id: photoId,
            poiId: command.PoiId,
            userId: _currentUser.UserId,
            authorDisplayName: _currentUser.DisplayName,
            objectKey: uploadResult.ObjectKey,
            imageUrl: uploadResult.StorageUrl,
            caption: command.Caption,
            capturedLatitude: command.CapturedLatitude,
            capturedLongitude: command.CapturedLongitude,
            distanceFromPoiMeters: actualDistanceMeters,
            azimuthDegrees: command.AzimuthDegrees,
            pitchDegrees: command.PitchDegrees,
            createdAt: now);

        _dbContext.CommunityPhotos.Add(photo);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToCommunityPhotoDto(photo, isLikedByCurrentUser: false));
    }

    public async Task<Result<ToggleLikeResponse>> ToggleLikeAsync(
        Guid photoId,
        CancellationToken ct = default)
    {
        var photo = await _dbContext.CommunityPhotos
            .FirstOrDefaultAsync(p => p.Id == photoId && p.Status == CommunityPhotoStatus.Published, ct);

        if (photo is null)
        {
            return Result.Failure<ToggleLikeResponse>(new Error(
                ErrorCodes.PhotoNotFound,
                "Community photo was not found.",
                ErrorType.NotFound));
        }

        var existingLike = await _dbContext.PhotoLikes
            .FirstOrDefaultAsync(l => l.PhotoId == photoId && l.UserId == _currentUser.UserId, ct);

        var now = _dateTimeProvider.UtcNow;
        bool isLiked;

        if (existingLike is not null)
        {
            _dbContext.PhotoLikes.Remove(existingLike);
            photo.DecrementLike();
            photo.UpdatedAt = now;
            isLiked = false;
        }
        else
        {
            var newLike = new PhotoLike(photoId, _currentUser.UserId, now);
            _dbContext.PhotoLikes.Add(newLike);
            photo.IncrementLike();
            photo.UpdatedAt = now;
            isLiked = true;
        }

        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(new ToggleLikeResponse(photo.Id, isLiked, photo.LikeCount));
    }

    public async Task<Result> ReportPhotoAsync(
        Guid photoId,
        string reason,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(reason) || reason.Trim().Length < 5)
        {
            return Result.Failure(new Error(
                "PHOTO_REPORT_REASON_INVALID",
                "Report reason must be at least 5 characters long.",
                ErrorType.Validation));
        }

        var photo = await _dbContext.CommunityPhotos
            .FirstOrDefaultAsync(
                p => p.Id == photoId &&
                     (p.Status == CommunityPhotoStatus.Published || p.Status == CommunityPhotoStatus.Flagged),
                ct);

        if (photo is null)
        {
            return Result.Failure(new Error(
                ErrorCodes.PhotoNotFound,
                "Community photo was not found.",
                ErrorType.NotFound));
        }

        var alreadyReported = await _dbContext.PhotoReports
            .AnyAsync(r => r.PhotoId == photoId && r.ReporterUserId == _currentUser.UserId, ct);

        if (alreadyReported)
        {
            return Result.Failure(new Error(
                ErrorCodes.PhotoAlreadyReported,
                "You have already reported this photo.",
                ErrorType.Conflict));
        }

        var report = photo.RegisterReport(_currentUser.UserId, reason.Trim(), _dateTimeProvider.UtcNow);
        _dbContext.PhotoReports.Add(report);

        await _dbContext.SaveChangesAsync(ct);

        return Result.Success();
    }

    public async Task<Result> DeleteOwnPhotoAsync(
        Guid photoId,
        CancellationToken ct = default)
    {
        var photo = await _dbContext.CommunityPhotos
            .FirstOrDefaultAsync(p => p.Id == photoId && p.Status != CommunityPhotoStatus.DeletedByOwner, ct);

        if (photo is null)
        {
            return Result.Failure(new Error(
                ErrorCodes.PhotoNotFound,
                "Community photo was not found.",
                ErrorType.NotFound));
        }

        if (photo.UserId != _currentUser.UserId && !_currentUser.IsSuperAdmin)
        {
            return Result.Failure(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only the photo owner or a SuperAdmin can delete this photo.",
                ErrorType.Forbidden));
        }

        photo.DeleteByOwner(_dateTimeProvider.UtcNow);
        await _objectStorageService.DeleteObjectAsync(photo.ObjectKey, ct);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success();
    }

    public async Task<Result<PagedResponse<CommunityPhotoDto>>> GetMyPhotosAsync(
        int page,
        int pageSize,
        CancellationToken ct = default)
    {
        var normalizedPage = Math.Max(1, page);
        var normalizedPageSize = Math.Clamp(pageSize <= 0 ? 20 : pageSize, 1, 100);

        var query = _dbContext.CommunityPhotos
            .AsNoTracking()
            .Where(p => p.UserId == _currentUser.UserId && p.Status != CommunityPhotoStatus.DeletedByOwner)
            .OrderByDescending(p => p.CreatedAt);

        var totalCount = await query.CountAsync(ct);
        var photos = await query
            .Skip((normalizedPage - 1) * normalizedPageSize)
            .Take(normalizedPageSize)
            .ToListAsync(ct);

        var likedPhotoIds = await GetLikedPhotoIdsForCurrentUserAsync(photos.Select(p => p.Id), ct);

        var items = photos
            .Select(p => MapToCommunityPhotoDto(p, likedPhotoIds.Contains(p.Id)))
            .ToList();

        return Result.Success(new PagedResponse<CommunityPhotoDto>(
            items,
            normalizedPage,
            normalizedPageSize,
            totalCount));
    }

    public async Task<Result<PagedResponse<AdminReportedPhotoDto>>> GetModerationQueueAsync(
        int page,
        int pageSize,
        CancellationToken ct = default)
    {
        if (!_currentUser.IsAdminOrSuperAdmin)
        {
            return Result.Failure<PagedResponse<AdminReportedPhotoDto>>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Admin or SuperAdmin role is required.",
                ErrorType.Forbidden));
        }

        var normalizedPage = Math.Max(1, page);
        var normalizedPageSize = Math.Clamp(pageSize <= 0 ? 20 : pageSize, 1, 100);

        var query = _dbContext.CommunityPhotos
            .AsNoTracking()
            .Include(p => p.Reports)
            .Where(p => p.Status == CommunityPhotoStatus.Flagged || p.ReportCount > 0)
            .OrderByDescending(p => p.Status == CommunityPhotoStatus.Flagged)
            .ThenByDescending(p => p.ReportCount)
            .ThenByDescending(p => p.CreatedAt);

        var totalCount = await query.CountAsync(ct);
        var photos = await query
            .Skip((normalizedPage - 1) * normalizedPageSize)
            .Take(normalizedPageSize)
            .ToListAsync(ct);

        var items = photos.Select(MapToAdminReportedPhotoDto).ToList();

        return Result.Success(new PagedResponse<AdminReportedPhotoDto>(
            items,
            normalizedPage,
            normalizedPageSize,
            totalCount));
    }

    public async Task<Result<AdminReportedPhotoDto>> ModeratePhotoAsync(
        Guid photoId,
        ModerationDecisionRequest request,
        CancellationToken ct = default)
    {
        if (!_currentUser.IsAdminOrSuperAdmin)
        {
            return Result.Failure<AdminReportedPhotoDto>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Admin or SuperAdmin role is required.",
                ErrorType.Forbidden));
        }

        var photo = await _dbContext.CommunityPhotos
            .Include(p => p.Reports)
            .FirstOrDefaultAsync(p => p.Id == photoId, ct);

        if (photo is null)
        {
            return Result.Failure<AdminReportedPhotoDto>(new Error(
                ErrorCodes.PhotoNotFound,
                "Community photo was not found.",
                ErrorType.NotFound));
        }

        var now = _dateTimeProvider.UtcNow;

        if (string.Equals(request.Action, "Approve", StringComparison.OrdinalIgnoreCase))
        {
            photo.ModerateApprove(_currentUser.UserId, request.Note, now);
        }
        else if (string.Equals(request.Action, "Remove", StringComparison.OrdinalIgnoreCase))
        {
            photo.ModerateRemove(_currentUser.UserId, request.Note, now);

            if (request.HardDeleteFromStorage)
            {
                await _objectStorageService.DeleteObjectAsync(photo.ObjectKey, ct);
            }
        }
        else
        {
            return Result.Failure<AdminReportedPhotoDto>(new Error(
                "INVALID_MODERATION_ACTION",
                "Moderation action must be either 'Approve' or 'Remove'.",
                ErrorType.Validation));
        }

        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToAdminReportedPhotoDto(photo));
    }

    private async Task<HashSet<Guid>> GetLikedPhotoIdsForCurrentUserAsync(
        IEnumerable<Guid> photoIds,
        CancellationToken ct)
    {
        if (!_currentUser.IsAuthenticated || _currentUser.UserId == Guid.Empty)
        {
            return new HashSet<Guid>();
        }

        var idList = photoIds.Distinct().ToList();
        if (idList.Count == 0)
        {
            return new HashSet<Guid>();
        }

        var likedIds = await _dbContext.PhotoLikes
            .AsNoTracking()
            .Where(l => l.UserId == _currentUser.UserId && idList.Contains(l.PhotoId))
            .Select(l => l.PhotoId)
            .ToListAsync(ct);

        return likedIds.ToHashSet();
    }

    private string GetImageUrl(CommunityPhoto photo) =>
        string.IsNullOrWhiteSpace(photo.ObjectKey)
            ? photo.ImageUrl
            : _objectStorageService.CreateReadUrl(photo.ObjectKey);

    private CommunityPhotoDto MapToCommunityPhotoDto(CommunityPhoto photo, bool isLikedByCurrentUser) =>
        new(
            photo.Id,
            photo.PoiId,
            photo.UserId,
            photo.AuthorDisplayName,
            GetImageUrl(photo),
            photo.Caption,
            photo.CapturedLatitude,
            photo.CapturedLongitude,
            photo.DistanceFromPoiMeters,
            photo.AzimuthDegrees,
            photo.PitchDegrees,
            photo.LikeCount,
            isLikedByCurrentUser,
            photo.CreatedAt);

    private AdminReportedPhotoDto MapToAdminReportedPhotoDto(CommunityPhoto photo) =>
        new(
            photo.Id,
            photo.PoiId,
            photo.UserId,
            photo.AuthorDisplayName,
            GetImageUrl(photo),
            photo.Caption,
            photo.Status.ToString(),
            photo.LikeCount,
            photo.ReportCount,
            photo.CreatedAt,
            photo.Reports
                .OrderByDescending(r => r.CreatedAt)
                .Select(r => new PhotoReportItemDto(
                    r.Id,
                    r.ReporterUserId,
                    r.Reason,
                    r.Status.ToString(),
                    r.CreatedAt))
                .ToList());
}
