using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.ArExperience.Application;
using SightseeingTravel.Modules.ArExperience.Domain;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Contracts;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.ArExperience.Infrastructure;

public sealed class ArExperienceService : IArExperienceService
{
    private const int DefaultArHighlightedPhotoLimit = 10;

    private readonly ArExperienceDbContext _dbContext;
    private readonly IAttractionGeofenceQueryService _attractionGeofenceQueryService;
    private readonly ICommunityPhotoArQueryService _communityPhotoArQueryService;
    private readonly ICurrentUser _currentUser;
    private readonly IDateTimeProvider _dateTimeProvider;
    private readonly IObjectStorageService? _objectStorageService;

    public ArExperienceService(
        ArExperienceDbContext dbContext,
        IAttractionGeofenceQueryService attractionGeofenceQueryService,
        ICommunityPhotoArQueryService communityPhotoArQueryService,
        ICurrentUser currentUser,
        IDateTimeProvider dateTimeProvider,
        IObjectStorageService? objectStorageService = null)
    {
        _dbContext = dbContext;
        _attractionGeofenceQueryService = attractionGeofenceQueryService;
        _communityPhotoArQueryService = communityPhotoArQueryService;
        _currentUser = currentUser;
        _dateTimeProvider = dateTimeProvider;
        _objectStorageService = objectStorageService;
    }

    public async Task<Result<ArContextResponse>> GetArContextForPoiAsync(
        Guid poiId,
        double userLatitude,
        double userLongitude,
        string? languageCode = null,
        CancellationToken ct = default)
    {
        if (!GeoCalculator.IsValidCoordinate(userLatitude, userLongitude))
        {
            return Result.Failure<ArContextResponse>(new Error(
                "INVALID_COORDINATES",
                "Provided user GPS coordinates (lat/lng) are out of valid geographic bounds.",
                ErrorType.Validation));
        }

        var effectiveLanguage = string.IsNullOrWhiteSpace(languageCode)
            ? _currentUser.PreferredLanguage
            : languageCode;

        var poi = await _attractionGeofenceQueryService.GetPublishedPoiGeofenceAsync(
            poiId,
            effectiveLanguage,
            ct);

        if (poi is null || !poi.IsPublished)
        {
            return Result.Failure<ArContextResponse>(new Error(
                ErrorCodes.PoiNotFound,
                $"Published attraction POI '{poiId}' was not found.",
                ErrorType.NotFound));
        }

        if (!GeoCalculator.IsWithinGeofence(
                userLatitude,
                userLongitude,
                poi.Latitude,
                poi.Longitude,
                poi.ActivationRadiusMeters,
                out var actualDistanceMeters))
        {
            var maxAllowedMeters = poi.ActivationRadiusMeters + GeoCalculator.DefaultGpsToleranceMeters;
            return Result.Failure<ArContextResponse>(new Error(
                ErrorCodes.PoiOutOfGeofenceRange,
                $"User is {actualDistanceMeters:F1}m away from '{poi.LocalizedName}', which exceeds the allowed AR geofence radius of {poi.ActivationRadiusMeters:F0}m (max {maxAllowedMeters:F0}m including {GeoCalculator.DefaultGpsToleranceMeters:F0}m GPS tolerance).",
                ErrorType.UnprocessableEntity));
        }

        var activeCards = await _dbContext.ArInfoCards
            .AsNoTracking()
            .Include(x => x.Localizations)
            .Where(x => x.PoiId == poiId && x.IsActive)
            .OrderBy(x => x.DisplayOrder)
            .ThenBy(x => x.CreatedAt)
            .ToListAsync(ct);

        var arCardDtos = new List<ArInfoCardDto>(activeCards.Count);
        foreach (var card in activeCards)
        {
            var localization = LanguageFallbackResolver.ResolveBestLocalization(
                card.Localizations,
                x => x.LanguageCode,
                effectiveLanguage);

            if (localization is null)
            {
                continue;
            }

            arCardDtos.Add(new ArInfoCardDto(
                card.Id,
                card.CardType.ToString(),
                localization.Title,
                localization.Subtitle,
                localization.Content,
                card.AzimuthDegrees,
                card.DistanceMeters,
                card.ElevationMeters,
                card.DisplayOrder,
                card.ModelObjectKey is null || _objectStorageService is null
                    ? null
                    : _objectStorageService.CreateReadUrl(card.ModelObjectKey),
                card.ModelRotationDegrees));
        }

        var highlightedPhotos = await _communityPhotoArQueryService.GetTopPhotosForArAsync(
            poiId,
            _currentUser.UserId,
            DefaultArHighlightedPhotoLimit,
            ct);

        var response = new ArContextResponse(
            poi.PoiId,
            poi.LocalizedName,
            poi.Latitude,
            poi.Longitude,
            actualDistanceMeters,
            arCardDtos,
            highlightedPhotos);

        return Result.Success(response);
    }

    public async Task<Result<IReadOnlyList<AdminArInfoCardDto>>> GetArCardsByPoiForAdminAsync(
        Guid poiId,
        CancellationToken ct = default)
    {
        if (poiId == Guid.Empty)
        {
            return Result.Failure<IReadOnlyList<AdminArInfoCardDto>>(new Error(
                "AR_CARD_INVALID_POI",
                "POI identifier is required.",
                ErrorType.Validation));
        }

        var cards = await _dbContext.ArInfoCards
            .AsNoTracking()
            .Include(x => x.Localizations)
            .Where(x => x.PoiId == poiId)
            .OrderBy(x => x.DisplayOrder)
            .ThenBy(x => x.CreatedAt)
            .ToListAsync(ct);

        var dtos = cards.Select(MapToAdminDto).ToList();
        return Result.Success<IReadOnlyList<AdminArInfoCardDto>>(dtos);
    }

    public async Task<Result<IReadOnlyList<ArModelLibraryItemDto>>> GetArModelLibraryAsync(
        CancellationToken ct = default)
    {
        if (_objectStorageService is null)
        {
            return Result.Failure<IReadOnlyList<ArModelLibraryItemDto>>(new Error(
                "AR_MODEL_STORAGE_UNAVAILABLE",
                "Object Storage is not configured, so the 3D model library cannot be loaded.",
                ErrorType.Failure));
        }

        var cards = await _dbContext.ArInfoCards
            .AsNoTracking()
            .Include(x => x.Localizations)
            .Where(x => x.ModelStatus == "Ready" && x.ModelObjectKey != null)
            .OrderBy(x => x.CreatedAt)
            .ToListAsync(ct);

        var uniqueModelCards = cards
            .Where(x => !string.IsNullOrWhiteSpace(x.ModelObjectKey))
            .GroupBy(x => x.ModelObjectKey!, StringComparer.Ordinal)
            .Select(group => group.OrderByDescending(x => x.CreatedAt).First());
        var items = uniqueModelCards
            .Select(card => new ArModelLibraryItemDto(
                card.Id,
                card.PoiId,
                card.Localizations
                    .OrderByDescending(x => string.Equals(x.LanguageCode, SupportedLanguages.Vietnamese, StringComparison.OrdinalIgnoreCase))
                    .ThenBy(x => x.LanguageCode)
                    .Select(x => x.Title)
                    .FirstOrDefault() ?? "Vật thể 3D",
                _objectStorageService.CreateReadUrl(card.ModelObjectKey!),
                card.ModelRotationDegrees))
            .ToList();

        return Result.Success<IReadOnlyList<ArModelLibraryItemDto>>(items);
    }

    public async Task<Result<AdminArInfoCardDto>> PlaceExistingModelAsync(
        Guid poiId,
        PlaceArModelRequest request,
        CancellationToken ct = default)
    {
        if (poiId == Guid.Empty)
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                "AR_CARD_INVALID_POI",
                "POI identifier is required.",
                ErrorType.Validation));
        }

        if (string.IsNullOrWhiteSpace(request.Name) || request.Name.Trim().Length > 200)
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                "AR_MODEL_NAME_REQUIRED",
                "A model name of 1 to 200 characters is required.",
                ErrorType.Validation));
        }

        if (_objectStorageService is null)
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                "AR_MODEL_STORAGE_UNAVAILABLE",
                "Object Storage is not configured, so a 3D model cannot be placed.",
                ErrorType.Failure));
        }

        var sourceCard = await _dbContext.ArInfoCards
            .AsNoTracking()
            .FirstOrDefaultAsync(
                x => x.Id == request.SourceCardId && x.ModelStatus == "Ready" && x.ModelObjectKey != null,
                ct);
        if (sourceCard is null || string.IsNullOrWhiteSpace(sourceCard.ModelObjectKey))
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                "AR_MODEL_LIBRARY_ITEM_NOT_FOUND",
                "The selected 3D model is no longer available in the library.",
                ErrorType.NotFound));
        }

        var nextDisplayOrder = await _dbContext.ArInfoCards
            .Where(x => x.PoiId == poiId)
            .Select(x => (int?)x.DisplayOrder)
            .MaxAsync(ct) ?? 0;
        nextDisplayOrder++;

        var nowUtc = _dateTimeProvider.UtcNow;
        var createResult = ArInfoCard.Create(
            poiId,
            ArCardType.StoryHighlight,
            request.AzimuthDegrees,
            request.DistanceMeters,
            request.ElevationMeters,
            nextDisplayOrder,
            isActive: true,
            nowUtc);
        if (createResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(createResult.Error);
        }

        var card = createResult.Value;
        var modelResult = card.AttachExistingModel(
            sourceCard.ModelObjectKey,
            request.ModelRotationDegrees,
            nowUtc);
        if (modelResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(modelResult.Error);
        }

        var normalizedName = request.Name.Trim();
        var localizationResult = card.ReplaceLocalizations(new[]
        {
            (SupportedLanguages.Vietnamese, normalizedName, (string?)null, normalizedName),
            (SupportedLanguages.English, normalizedName, (string?)null, normalizedName)
        });
        if (localizationResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(localizationResult.Error);
        }

        _dbContext.ArInfoCards.Add(card);
        await _dbContext.SaveChangesAsync(ct);
        return Result.Success(MapToAdminDto(card));
    }

    public async Task<Result<AdminArInfoCardDto>> CreateArCardAsync(
        Guid poiId,
        UpsertArInfoCardRequest request,
        CancellationToken ct = default)
    {
        if (!ArInfoCard.TryParseCardType(request.CardType, out var cardType))
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                "AR_CARD_INVALID_TYPE",
                $"Invalid AR card type '{request.CardType}'. Allowed values: {string.Join(", ", Enum.GetNames<ArCardType>())}.",
                ErrorType.Validation));
        }

        var nowUtc = _dateTimeProvider.UtcNow;
        var createResult = ArInfoCard.Create(
            poiId,
            cardType,
            request.AzimuthDegrees,
            request.DistanceMeters,
            request.ElevationMeters,
            request.DisplayOrder,
            request.IsActive,
            nowUtc);

        if (createResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(createResult.Error);
        }

        var card = createResult.Value;
        var localizationItems = (request.Localizations ?? Array.Empty<ArCardLocalizationInputDto>())
            .Select(l => (l.LanguageCode, l.Title, l.Subtitle, l.Content));

        var localizationResult = card.ReplaceLocalizations(localizationItems);
        if (localizationResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(localizationResult.Error);
        }

        _dbContext.ArInfoCards.Add(card);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToAdminDto(card));
    }

    public async Task<Result<AdminArInfoCardDto>> UpdateArCardAsync(
        Guid cardId,
        UpsertArInfoCardRequest request,
        CancellationToken ct = default)
    {
        var card = await _dbContext.ArInfoCards
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == cardId, ct);

        if (card is null)
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                ErrorCodes.ArCardNotFound,
                $"AR Info Card '{cardId}' was not found.",
                ErrorType.NotFound));
        }

        if (!ArInfoCard.TryParseCardType(request.CardType, out var cardType))
        {
            return Result.Failure<AdminArInfoCardDto>(new Error(
                "AR_CARD_INVALID_TYPE",
                $"Invalid AR card type '{request.CardType}'. Allowed values: {string.Join(", ", Enum.GetNames<ArCardType>())}.",
                ErrorType.Validation));
        }

        var nowUtc = _dateTimeProvider.UtcNow;
        var updateResult = card.Update(
            cardType,
            request.AzimuthDegrees,
            request.DistanceMeters,
            request.ElevationMeters,
            request.DisplayOrder,
            request.IsActive,
            nowUtc);

        if (updateResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(updateResult.Error);
        }

        var existingLocalizations = card.Localizations.ToList();
        var localizationItems = (request.Localizations ?? Array.Empty<ArCardLocalizationInputDto>())
            .Select(l => (l.LanguageCode, l.Title, l.Subtitle, l.Content));

        var localizationResult = card.ReplaceLocalizations(localizationItems);
        if (localizationResult.IsFailure)
        {
            return Result.Failure<AdminArInfoCardDto>(localizationResult.Error);
        }

        if (existingLocalizations.Count > 0)
        {
            _dbContext.ArInfoCardLocalizations.RemoveRange(existingLocalizations);
        }

        _dbContext.ArInfoCardLocalizations.AddRange(card.Localizations);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToAdminDto(card));
    }

    public async Task<Result> DeleteArCardAsync(
        Guid cardId,
        CancellationToken ct = default)
    {
        var card = await _dbContext.ArInfoCards
            .FirstOrDefaultAsync(x => x.Id == cardId, ct);

        if (card is null)
        {
            return Result.Failure(new Error(
                ErrorCodes.ArCardNotFound,
                $"AR Info Card '{cardId}' was not found.",
                ErrorType.NotFound));
        }

        var modelObjectKey = card.ModelObjectKey;
        var modelIsShared = !string.IsNullOrWhiteSpace(modelObjectKey) &&
            await _dbContext.ArInfoCards.AnyAsync(
                x => x.Id != cardId && x.ModelObjectKey == modelObjectKey,
                ct);

        _dbContext.ArInfoCards.Remove(card);
        await _dbContext.SaveChangesAsync(ct);

        if (!modelIsShared && !string.IsNullOrWhiteSpace(modelObjectKey) && _objectStorageService is not null)
        {
            await _objectStorageService.DeleteObjectAsync(modelObjectKey, ct);
        }

        return Result.Success();
    }

    private static AdminArInfoCardDto MapToAdminDto(ArInfoCard card)
    {
        var localizations = card.Localizations
            .OrderBy(x => x.LanguageCode)
            .Select(x => new ArCardLocalizationInputDto(
                x.LanguageCode,
                x.Title,
                x.Subtitle,
                x.Content))
            .ToList();

        return new AdminArInfoCardDto(
            card.Id,
            card.PoiId,
            card.CardType.ToString(),
            card.AzimuthDegrees,
            card.DistanceMeters,
            card.ElevationMeters,
            card.DisplayOrder,
            card.IsActive,
            localizations,
            card.ModelStatus,
            card.ModelError,
            card.ModelRotationDegrees);
    }
}
