namespace SightseeingTravel.Modules.CommunityMedia.Application;

public sealed record CommunityPhotoDto(
    Guid Id,
    Guid PoiId,
    Guid UserId,
    string AuthorDisplayName,
    string ImageUrl,
    string? Caption,
    double CapturedLatitude,
    double CapturedLongitude,
    double DistanceFromPoiMeters,
    double? AzimuthDegrees,
    int LikeCount,
    bool IsLikedByCurrentUser,
    DateTimeOffset CreatedAt);

public sealed record UploadPhotoCommand(
    Guid PoiId,
    Stream FileStream,
    long FileLength,
    string? ContentType,
    double CapturedLatitude,
    double CapturedLongitude,
    double? AzimuthDegrees,
    string? Caption);

public sealed record ToggleLikeResponse(
    Guid PhotoId,
    bool IsLiked,
    int LikeCount);

public sealed record ReportPhotoRequest(
    string Reason);

public sealed record ModerationDecisionRequest(
    string Action,
    string? Note,
    bool HardDeleteFromStorage = false);

public sealed record AdminReportedPhotoDto(
    Guid PhotoId,
    Guid PoiId,
    Guid AuthorUserId,
    string AuthorDisplayName,
    string ImageUrl,
    string? Caption,
    string Status,
    int LikeCount,
    int ReportCount,
    DateTimeOffset CreatedAt,
    IReadOnlyList<PhotoReportItemDto> Reports);

public sealed record PhotoReportItemDto(
    Guid ReportId,
    Guid ReporterUserId,
    string Reason,
    string Status,
    DateTimeOffset CreatedAt);
