using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.ArExperience.Application;

public sealed record ArInfoCardDto(
    Guid Id,
    string CardType,
    string Title,
    string? Subtitle,
    string Content,
    double AzimuthDegrees,
    double DistanceMeters,
    double ElevationMeters,
    int DisplayOrder);

public sealed record ArContextResponse(
    Guid PoiId,
    string PoiName,
    double PoiLatitude,
    double PoiLongitude,
    double UserDistanceMeters,
    IReadOnlyList<ArInfoCardDto> ArInfoCards,
    IReadOnlyList<ArHighlightedPhotoDto> HighlightedCommunityPhotos);

public sealed record ArCardLocalizationInputDto(
    string LanguageCode,
    string Title,
    string? Subtitle,
    string Content);

public sealed record UpsertArInfoCardRequest(
    string CardType,
    double AzimuthDegrees,
    double DistanceMeters,
    double ElevationMeters,
    int DisplayOrder,
    bool IsActive,
    IReadOnlyList<ArCardLocalizationInputDto> Localizations);

public sealed record AdminArInfoCardDto(
    Guid Id,
    Guid PoiId,
    string CardType,
    double AzimuthDegrees,
    double DistanceMeters,
    double ElevationMeters,
    int DisplayOrder,
    bool IsActive,
    IReadOnlyList<ArCardLocalizationInputDto> Localizations);
