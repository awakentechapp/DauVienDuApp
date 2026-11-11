namespace SightseeingTravel.Modules.Attractions.Application;

public sealed record NearbyPoiItemDto(
    Guid Id,
    string Code,
    string Category,
    string Name,
    string ShortSummary,
    double Latitude,
    double Longitude,
    double ActivationRadiusMeters,
    double DistanceMeters,
    bool CanActivateAr,
    string? CoverImageUrl,
    int CommunityPhotoCount);

public sealed record NearbyPoisResponse(
    IReadOnlyList<NearbyPoiItemDto> Items);

public sealed record PoiDetailDto(
    Guid Id,
    string Code,
    string Category,
    string LanguageCode,
    string Name,
    string ShortSummary,
    string CulturalStoryMarkdown,
    string? HistoricalPeriod,
    double Latitude,
    double Longitude,
    double ActivationRadiusMeters,
    double? DistanceMeters,
    bool CanActivateAr,
    string? CoverImageUrl,
    int CommunityPhotoCount,
    DateTimeOffset UpdatedAt);

public sealed record PoiLocalizationInputDto(
    string LanguageCode,
    string Name,
    string ShortSummary,
    string CulturalStoryMarkdown,
    string? HistoricalPeriod);

public sealed record CreatePoiRequest(
    string Code,
    double Latitude,
    double Longitude,
    double ActivationRadiusMeters,
    bool SaveAsDraft,
    string? CoverImageUrl,
    IReadOnlyList<PoiLocalizationInputDto> Localizations,
    string? Category = null);

public sealed record UpdatePoiRequest(
    double Latitude,
    double Longitude,
    double ActivationRadiusMeters,
    string? CoverImageUrl,
    IReadOnlyList<PoiLocalizationInputDto> Localizations,
    string? Category = null);

public sealed record RejectPoiRequest(
    string Reason);

public sealed record AdminPoiDto(
    Guid Id,
    string Code,
    string Category,
    double Latitude,
    double Longitude,
    double ActivationRadiusMeters,
    string Status,
    string? RejectionReason,
    string? CoverImageUrl,
    Guid CreatedBy,
    Guid? ApprovedBy,
    DateTimeOffset? ApprovedAt,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    IReadOnlyList<PoiLocalizationInputDto> Localizations);
