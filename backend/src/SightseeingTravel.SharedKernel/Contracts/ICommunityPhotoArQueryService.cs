namespace SightseeingTravel.SharedKernel.Contracts;

public sealed record ArHighlightedPhotoDto(
    Guid Id,
    string ImageUrl,
    string? Caption,
    string AuthorDisplayName,
    int LikeCount,
    bool IsLikedByCurrentUser,
    double? AzimuthDegrees,
    DateTimeOffset CreatedAt,
    double CapturedLatitude = 0.0,
    double CapturedLongitude = 0.0,
    double ElevationMeters = 1.5,
    double ScaleFactor = 1.0);

public interface ICommunityPhotoArQueryService
{
    Task<IReadOnlyList<ArHighlightedPhotoDto>> GetTopPhotosForArAsync(
        Guid poiId,
        Guid currentUserId,
        int maxCount = 10,
        CancellationToken cancellationToken = default);

    Task<Dictionary<Guid, int>> GetPublishedPhotoCountsByPoiIdsAsync(
        IEnumerable<Guid> poiIds,
        CancellationToken cancellationToken = default);
}
