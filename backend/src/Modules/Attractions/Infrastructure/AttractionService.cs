using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.Attractions.Application;
using SightseeingTravel.Modules.Attractions.Domain;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Contracts;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Attractions.Infrastructure;

public sealed class AttractionService : IAttractionService
{
    private readonly AttractionsDbContext _dbContext;
    private readonly ICurrentUser _currentUser;
    private readonly IDateTimeProvider _dateTimeProvider;
    private readonly IObjectStorageService _objectStorageService;
    private readonly ICommunityPhotoArQueryService _communityPhotoQueryService;

    public AttractionService(
        AttractionsDbContext dbContext,
        ICurrentUser currentUser,
        IDateTimeProvider dateTimeProvider,
        IObjectStorageService objectStorageService,
        ICommunityPhotoArQueryService communityPhotoQueryService)
    {
        _dbContext = dbContext;
        _currentUser = currentUser;
        _dateTimeProvider = dateTimeProvider;
        _objectStorageService = objectStorageService;
        _communityPhotoQueryService = communityPhotoQueryService;
    }

    public async Task<Result<NearbyPoisResponse>> GetNearbyPoisAsync(
        double latitude,
        double longitude,
        double radiusKm = 10.0,
        string? languageCode = null,
        CancellationToken cancellationToken = default)
    {
        if (!GeoCalculator.IsValidCoordinate(latitude, longitude))
        {
            return Result.Failure<NearbyPoisResponse>(new Error(
                "POI_INVALID_COORDINATES",
                "Valid user latitude (-90..90) and longitude (-180..180) are required.",
                ErrorType.Validation));
        }

        var normalizedRadiusKm = Math.Clamp(radiusKm <= 0 ? 10.0 : radiusKm, 0.1, 200.0);
        var maxDistanceMeters = normalizedRadiusKm * 1000.0;
        var (minLat, maxLat, minLng, maxLng) = GeoCalculator.CalculateBoundingBox(
            latitude,
            longitude,
            normalizedRadiusKm);

        var candidates = await _dbContext.PointsOfInterest
            .AsNoTracking()
            .Include(x => x.Localizations)
            .Where(x =>
                x.Status == PoiStatus.Published &&
                x.Latitude >= minLat && x.Latitude <= maxLat &&
                x.Longitude >= minLng && x.Longitude <= maxLng)
            .ToListAsync(cancellationToken);

        var matched = new List<(PointOfInterest Poi, double DistanceMeters, bool CanActivateAr)>();
        foreach (var poi in candidates)
        {
            var canActivateAr = GeoCalculator.IsWithinGeofence(
                latitude,
                longitude,
                poi.Latitude,
                poi.Longitude,
                poi.ActivationRadiusMeters,
                out var distanceMeters);

            if (distanceMeters <= maxDistanceMeters)
            {
                matched.Add((poi, distanceMeters, canActivateAr));
            }
        }

        var photoCounts = matched.Count > 0
            ? await _communityPhotoQueryService.GetPublishedPhotoCountsByPoiIdsAsync(
                matched.Select(x => x.Poi.Id),
                cancellationToken)
            : new Dictionary<Guid, int>();

        var effectiveLang = string.IsNullOrWhiteSpace(languageCode)
            ? _currentUser.PreferredLanguage
            : languageCode;

        var items = matched
            .OrderBy(x => x.DistanceMeters)
            .Select(x =>
            {
                var loc = LanguageFallbackResolver.ResolveBestLocalization(
                    x.Poi.Localizations,
                    l => l.LanguageCode,
                    effectiveLang);

                photoCounts.TryGetValue(x.Poi.Id, out var photoCount);

                return new NearbyPoiItemDto(
                    x.Poi.Id,
                    x.Poi.Code,
                    loc?.Name ?? x.Poi.Code,
                    loc?.ShortSummary ?? string.Empty,
                    x.Poi.Latitude,
                    x.Poi.Longitude,
                    x.Poi.ActivationRadiusMeters,
                    x.DistanceMeters,
                    x.CanActivateAr,
                    x.Poi.CoverImageUrl,
                    photoCount);
            })
            .ToList();

        return Result.Success(new NearbyPoisResponse(items));
    }

    public async Task<Result<PoiDetailDto>> GetPoiDetailAsync(
        Guid poiId,
        double? userLatitude = null,
        double? userLongitude = null,
        string? languageCode = null,
        CancellationToken cancellationToken = default)
    {
        var poi = await _dbContext.PointsOfInterest
            .AsNoTracking()
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(
                x => x.Id == poiId && x.Status == PoiStatus.Published,
                cancellationToken);

        if (poi is null)
        {
            return Result.Failure<PoiDetailDto>(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found or is not published.",
                ErrorType.NotFound));
        }

        double? distanceMeters = null;
        var canActivateAr = false;

        if (userLatitude.HasValue &&
            userLongitude.HasValue &&
            GeoCalculator.IsValidCoordinate(userLatitude.Value, userLongitude.Value))
        {
            canActivateAr = GeoCalculator.IsWithinGeofence(
                userLatitude.Value,
                userLongitude.Value,
                poi.Latitude,
                poi.Longitude,
                poi.ActivationRadiusMeters,
                out var actualDistance);

            distanceMeters = actualDistance;
        }

        var photoCounts = await _communityPhotoQueryService.GetPublishedPhotoCountsByPoiIdsAsync(
            new[] { poi.Id },
            cancellationToken);
        photoCounts.TryGetValue(poi.Id, out var photoCount);

        var effectiveLang = string.IsNullOrWhiteSpace(languageCode)
            ? _currentUser.PreferredLanguage
            : languageCode;

        var loc = LanguageFallbackResolver.ResolveBestLocalization(
            poi.Localizations,
            l => l.LanguageCode,
            effectiveLang);

        var dto = new PoiDetailDto(
            poi.Id,
            poi.Code,
            loc?.LanguageCode ?? SupportedLanguages.Normalize(effectiveLang),
            loc?.Name ?? poi.Code,
            loc?.ShortSummary ?? string.Empty,
            loc?.CulturalStoryMarkdown ?? string.Empty,
            loc?.HistoricalPeriod,
            poi.Latitude,
            poi.Longitude,
            poi.ActivationRadiusMeters,
            distanceMeters,
            canActivateAr,
            poi.CoverImageUrl,
            photoCount,
            poi.UpdatedAt);

        return Result.Success(dto);
    }

    public async Task<Result<IReadOnlyList<AdminPoiDto>>> GetAdminPoisAsync(
        string? statusFilter = null,
        CancellationToken cancellationToken = default)
    {
        var query = _dbContext.PointsOfInterest
            .AsNoTracking()
            .Include(x => x.Localizations)
            .AsQueryable();

        if (!string.IsNullOrWhiteSpace(statusFilter) &&
            Enum.TryParse<PoiStatus>(statusFilter.Trim(), ignoreCase: true, out var parsedStatus))
        {
            query = query.Where(x => x.Status == parsedStatus);
        }

        var pois = await query
            .OrderByDescending(x => x.UpdatedAt)
            .ToListAsync(cancellationToken);

        var items = pois.Select(MapToAdminDto).ToList();
        return Result.Success<IReadOnlyList<AdminPoiDto>>(items);
    }

    public async Task<Result<AdminPoiDto>> CreatePoiAsync(
        CreatePoiRequest request,
        CancellationToken cancellationToken = default)
    {
        var validationError = ValidateLocalizations(request.Localizations);
        if (validationError is not null)
        {
            return Result.Failure<AdminPoiDto>(validationError);
        }

        if (string.IsNullOrWhiteSpace(request.Code))
        {
            return Result.Failure<AdminPoiDto>(new Error(
                "POI_CODE_REQUIRED",
                "POI code is required.",
                ErrorType.Validation));
        }

        var normalizedCode = PointOfInterest.NormalizeCode(request.Code);
        var codeExists = await _dbContext.PointsOfInterest
            .AnyAsync(x => x.Code == normalizedCode, cancellationToken);

        if (codeExists)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.PoiCodeAlreadyExists,
                $"A Point of Interest with code '{normalizedCode}' already exists.",
                ErrorType.Conflict));
        }

        var now = _dateTimeProvider.UtcNow;
        var createResult = PointOfInterest.Create(
            normalizedCode,
            request.Latitude,
            request.Longitude,
            request.ActivationRadiusMeters,
            request.CoverImageUrl,
            _currentUser.UserId,
            _currentUser.Role,
            request.SaveAsDraft,
            now);

        if (createResult.IsFailure)
        {
            return Result.Failure<AdminPoiDto>(createResult.Error);
        }

        var poi = createResult.Value;

        foreach (var locInput in DeduplicateLocalizations(request.Localizations))
        {
            poi.Localizations.Add(new PoiLocalization(
                Guid.NewGuid(),
                poi.Id,
                locInput.LanguageCode,
                locInput.Name,
                locInput.ShortSummary,
                locInput.CulturalStoryMarkdown,
                locInput.HistoricalPeriod));
        }

        _dbContext.PointsOfInterest.Add(poi);
        await _dbContext.SaveChangesAsync(cancellationToken);

        return Result.Success(MapToAdminDto(poi));
    }

    public async Task<Result<AdminPoiDto>> UpdatePoiAsync(
        Guid poiId,
        UpdatePoiRequest request,
        CancellationToken cancellationToken = default)
    {
        var validationError = ValidateLocalizations(request.Localizations);
        if (validationError is not null)
        {
            return Result.Failure<AdminPoiDto>(validationError);
        }

        var poi = await _dbContext.PointsOfInterest
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == poiId, cancellationToken);

        if (poi is null)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found.",
                ErrorType.NotFound));
        }

        var now = _dateTimeProvider.UtcNow;
        var updateResult = poi.UpdateDetails(
            request.Latitude,
            request.Longitude,
            request.ActivationRadiusMeters,
            request.CoverImageUrl,
            _currentUser.Role,
            _currentUser.UserId,
            now);

        if (updateResult.IsFailure)
        {
            return Result.Failure<AdminPoiDto>(updateResult.Error);
        }

        var incomingByLang = DeduplicateLocalizations(request.Localizations)
            .ToDictionary(
                x => SupportedLanguages.Normalize(x.LanguageCode),
                StringComparer.OrdinalIgnoreCase);

        var toRemove = poi.Localizations
            .Where(existing => !incomingByLang.ContainsKey(SupportedLanguages.Normalize(existing.LanguageCode)))
            .ToList();

        foreach (var removed in toRemove)
        {
            poi.Localizations.Remove(removed);
            _dbContext.PoiLocalizations.Remove(removed);
        }

        foreach (var (langCode, input) in incomingByLang)
        {
            var existing = poi.Localizations.FirstOrDefault(
                x => string.Equals(SupportedLanguages.Normalize(x.LanguageCode), langCode, StringComparison.OrdinalIgnoreCase));

            if (existing is not null)
            {
                existing.UpdateContent(
                    input.Name,
                    input.ShortSummary,
                    input.CulturalStoryMarkdown,
                    input.HistoricalPeriod);
            }
            else
            {
                var added = new PoiLocalization(
                    Guid.NewGuid(),
                    poi.Id,
                    langCode,
                    input.Name,
                    input.ShortSummary,
                    input.CulturalStoryMarkdown,
                    input.HistoricalPeriod);
                poi.Localizations.Add(added);
                _dbContext.PoiLocalizations.Add(added);
            }
        }

        await _dbContext.SaveChangesAsync(cancellationToken);
        return Result.Success(MapToAdminDto(poi));
    }

    public async Task<Result<AdminPoiDto>> UploadCoverImageAsync(
        Guid poiId,
        Stream fileStream,
        long lengthBytes,
        string? contentType,
        CancellationToken cancellationToken = default)
    {
        var poi = await _dbContext.PointsOfInterest
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == poiId, cancellationToken);

        if (poi is null)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found.",
                ErrorType.NotFound));
        }

        var signatureResult = ImageSignatureValidator.ValidateAndGetExtension(
            fileStream,
            lengthBytes,
            contentType);

        if (signatureResult.IsFailure)
        {
            return Result.Failure<AdminPoiDto>(signatureResult.Error);
        }

        var ext = signatureResult.Value;
        var resolvedContentType = ext switch
        {
            ".png" => "image/png",
            ".webp" => "image/webp",
            _ => "image/jpeg"
        };

        var fileId = Guid.NewGuid().ToString("N");
        var objectKey = $"pois/{poi.Id}/official/{fileId}{ext}";

        var previousObjectKey = poi.CoverObjectKey;
        var uploadResult = await _objectStorageService.UploadPhotoAsync(
            fileStream,
            resolvedContentType,
            objectKey,
            cancellationToken);

        poi.SetCoverImage(uploadResult.PublicUrl, uploadResult.ObjectKey, _dateTimeProvider.UtcNow);
        await _dbContext.SaveChangesAsync(cancellationToken);

        if (!string.IsNullOrWhiteSpace(previousObjectKey) &&
            !string.Equals(previousObjectKey, uploadResult.ObjectKey, StringComparison.Ordinal))
        {
            try
            {
                await _objectStorageService.DeleteObjectAsync(previousObjectKey, cancellationToken);
            }
            catch
            {
                // Best-effort cleanup of old cover object in S3 storage
            }
        }

        return Result.Success(MapToAdminDto(poi));
    }

    public async Task<Result<AdminPoiDto>> SubmitPoiForApprovalAsync(
        Guid poiId,
        CancellationToken cancellationToken = default)
    {
        var poi = await _dbContext.PointsOfInterest
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == poiId, cancellationToken);

        if (poi is null)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found.",
                ErrorType.NotFound));
        }

        var submitResult = poi.SubmitForApproval(_dateTimeProvider.UtcNow);
        if (submitResult.IsFailure)
        {
            return Result.Failure<AdminPoiDto>(submitResult.Error);
        }

        await _dbContext.SaveChangesAsync(cancellationToken);
        return Result.Success(MapToAdminDto(poi));
    }

    public async Task<Result<AdminPoiDto>> ApprovePoiAsync(
        Guid poiId,
        CancellationToken cancellationToken = default)
    {
        if (!_currentUser.IsSuperAdmin)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only SuperAdmin can approve a Point of Interest.",
                ErrorType.Forbidden));
        }

        var poi = await _dbContext.PointsOfInterest
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == poiId, cancellationToken);

        if (poi is null)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found.",
                ErrorType.NotFound));
        }

        var approveResult = poi.Approve(_currentUser.UserId, _dateTimeProvider.UtcNow);
        if (approveResult.IsFailure)
        {
            return Result.Failure<AdminPoiDto>(approveResult.Error);
        }

        await _dbContext.SaveChangesAsync(cancellationToken);
        return Result.Success(MapToAdminDto(poi));
    }

    public async Task<Result<AdminPoiDto>> RejectPoiAsync(
        Guid poiId,
        RejectPoiRequest request,
        CancellationToken cancellationToken = default)
    {
        if (!_currentUser.IsSuperAdmin)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only SuperAdmin can reject a Point of Interest.",
                ErrorType.Forbidden));
        }

        var poi = await _dbContext.PointsOfInterest
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == poiId, cancellationToken);

        if (poi is null)
        {
            return Result.Failure<AdminPoiDto>(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found.",
                ErrorType.NotFound));
        }

        var rejectResult = poi.Reject(_currentUser.UserId, request?.Reason, _dateTimeProvider.UtcNow);
        if (rejectResult.IsFailure)
        {
            return Result.Failure<AdminPoiDto>(rejectResult.Error);
        }

        await _dbContext.SaveChangesAsync(cancellationToken);
        return Result.Success(MapToAdminDto(poi));
    }

    public async Task<Result> DeletePoiAsync(
        Guid poiId,
        CancellationToken cancellationToken = default)
    {
        if (!_currentUser.IsSuperAdmin)
        {
            return Result.Failure(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only SuperAdmin can permanently delete a Point of Interest.",
                ErrorType.Forbidden));
        }

        var poi = await _dbContext.PointsOfInterest
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == poiId, cancellationToken);

        if (poi is null)
        {
            return Result.Failure(new Error(
                ErrorCodes.PoiNotFound,
                "Point of Interest was not found.",
                ErrorType.NotFound));
        }

        var coverObjectKey = poi.CoverObjectKey;
        _dbContext.PointsOfInterest.Remove(poi);
        await _dbContext.SaveChangesAsync(cancellationToken);

        if (!string.IsNullOrWhiteSpace(coverObjectKey))
        {
            try
            {
                await _objectStorageService.DeleteObjectAsync(coverObjectKey, cancellationToken);
            }
            catch
            {
                // Best-effort cleanup of cover object
            }
        }

        return Result.Success();
    }

    private static Error? ValidateLocalizations(IReadOnlyList<PoiLocalizationInputDto>? localizations)
    {
        if (localizations is null || localizations.Count == 0)
        {
            return new Error(
                "POI_LOCALIZATIONS_REQUIRED",
                "At least one localization (vi or en) is required.",
                ErrorType.Validation);
        }

        foreach (var loc in localizations)
        {
            if (string.IsNullOrWhiteSpace(loc.Name) ||
                string.IsNullOrWhiteSpace(loc.ShortSummary) ||
                string.IsNullOrWhiteSpace(loc.CulturalStoryMarkdown))
            {
                return new Error(
                    "POI_LOCALIZATION_INVALID",
                    "Each localization must include a non-empty Name, ShortSummary, and CulturalStoryMarkdown.",
                    ErrorType.Validation);
            }
        }

        return null;
    }

    private static IEnumerable<PoiLocalizationInputDto> DeduplicateLocalizations(
        IEnumerable<PoiLocalizationInputDto> localizations)
    {
        return localizations
            .GroupBy(x => SupportedLanguages.Normalize(x.LanguageCode), StringComparer.OrdinalIgnoreCase)
            .Select(g => g.Last());
    }

    private static AdminPoiDto MapToAdminDto(PointOfInterest poi)
    {
        var localizations = poi.Localizations
            .OrderBy(x => x.LanguageCode)
            .Select(x => new PoiLocalizationInputDto(
                x.LanguageCode,
                x.Name,
                x.ShortSummary,
                x.CulturalStoryMarkdown,
                x.HistoricalPeriod))
            .ToList();

        return new AdminPoiDto(
            poi.Id,
            poi.Code,
            poi.Latitude,
            poi.Longitude,
            poi.ActivationRadiusMeters,
            poi.Status.ToString(),
            poi.RejectionReason,
            poi.CoverImageUrl,
            poi.CreatedBy,
            poi.ApprovedBy,
            poi.ApprovedAt,
            poi.CreatedAt,
            poi.UpdatedAt,
            localizations);
    }
}
