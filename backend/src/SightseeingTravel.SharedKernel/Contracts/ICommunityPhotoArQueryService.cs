namespace SightseeingTravel.SharedKernel.Contracts;

public sealed record ArHighlightedPhotoDto(
    Guid Id,
    string ImageUrl,
    string? Caption,
    string AuthorDisplayName,
    int LikeCount,
    bool IsLikedByCurrentUser,
    double? AzimuthDegrees,
    DateTimeOffset CreatedAt);

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
