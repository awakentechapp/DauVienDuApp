using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.ArExperience.Application;
using SightseeingTravel.Modules.ArExperience.Domain;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.ArExperience.Infrastructure;

public sealed class ArExperienceService : IArExperienceService
{
    private const int DefaultArHighlightedPhotoLimit = 10;

    private readonly ArExperienceDbContext _dbContext;
    private readonly IAttractionGeofenceQueryService _attractionGeofenceQueryService;
    private readonly ICommunityPhotoArQueryService _communityPhotoArQueryService;
    private readonly ICurrentUser _currentUser;
    private readonly IDateTimeProvider _dateTimeProvider;

    public ArExperienceService(
        ArExperienceDbContext dbContext,
        IAttractionGeofenceQueryService attractionGeofenceQueryService,
        ICommunityPhotoArQueryService communityPhotoArQueryService,
        ICurrentUser currentUser,
        IDateTimeProvider dateTimeProvider)
    {
        _dbContext = dbContext;
        _attractionGeofenceQueryService = attractionGeofenceQueryService;
        _communityPhotoArQueryService = communityPhotoArQueryService;
        _currentUser = currentUser;
        _dateTimeProvider = dateTimeProvider;
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
                card.DisplayOrder));
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

        _dbContext.ArInfoCards.Remove(card);
        await _dbContext.SaveChangesAsync(ct);

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
            localizations);
    }
}
