using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.CommunityMedia.Domain;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.CommunityMedia.Infrastructure;

public sealed class CommunityPhotoArQueryService : ICommunityPhotoArQueryService
{
    private readonly CommunityMediaDbContext _dbContext;

    public CommunityPhotoArQueryService(CommunityMediaDbContext dbContext)
    {
        _dbContext = dbContext;
    }

    public async Task<IReadOnlyList<ArHighlightedPhotoDto>> GetTopPhotosForArAsync(
        Guid poiId,
        Guid currentUserId,
        int maxCount = 10,
        CancellationToken cancellationToken = default)
    {
        var takeCount = Math.Clamp(maxCount <= 0 ? 10 : maxCount, 1, 25);

        var topPhotos = await _dbContext.CommunityPhotos
            .AsNoTracking()
            .Where(p => p.PoiId == poiId && p.Status == CommunityPhotoStatus.Published)
            .OrderByDescending(p => p.LikeCount)
            .ThenByDescending(p => p.CreatedAt)
            .Take(takeCount)
            .ToListAsync(cancellationToken);

        if (topPhotos.Count == 0)
        {
            return Array.Empty<ArHighlightedPhotoDto>();
        }

        var likedSet = new HashSet<Guid>();
        if (currentUserId != Guid.Empty)
        {
            var photoIds = topPhotos.Select(p => p.Id).ToList();
            var likedIds = await _dbContext.PhotoLikes
                .AsNoTracking()
                .Where(l => l.UserId == currentUserId && photoIds.Contains(l.PhotoId))
                .Select(l => l.PhotoId)
                .ToListAsync(cancellationToken);

            likedSet = likedIds.ToHashSet();
        }

        return topPhotos
            .Select(p => new ArHighlightedPhotoDto(
                p.Id,
                p.ImageUrl,
                p.Caption,
                p.AuthorDisplayName,
                p.LikeCount,
                likedSet.Contains(p.Id),
                p.AzimuthDegrees,
                p.CreatedAt))
            .ToList();
    }

    public async Task<Dictionary<Guid, int>> GetPublishedPhotoCountsByPoiIdsAsync(
        IEnumerable<Guid> poiIds,
        CancellationToken cancellationToken = default)
    {
        var idList = poiIds.Distinct().ToList();
        if (idList.Count == 0)
        {
            return new Dictionary<Guid, int>();
        }

        return await _dbContext.CommunityPhotos
            .AsNoTracking()
            .Where(p => idList.Contains(p.PoiId) && p.Status == CommunityPhotoStatus.Published)
            .GroupBy(p => p.PoiId)
            .Select(g => new { PoiId = g.Key, Count = g.Count() })
            .ToDictionaryAsync(x => x.PoiId, x => x.Count, cancellationToken);
    }
}
