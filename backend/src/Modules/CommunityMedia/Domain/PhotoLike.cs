using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.CommunityMedia.Domain;

public sealed class PhotoLike : Entity<Guid>
{
    public Guid PhotoId { get; private set; }
    public Guid UserId { get; private set; }
    public DateTimeOffset CreatedAt { get; private set; }

    public CommunityPhoto? Photo { get; private set; }

    private PhotoLike()
    {
    }

    public PhotoLike(Guid photoId, Guid userId, DateTimeOffset createdAt)
    {
        Id = Guid.NewGuid();
        PhotoId = photoId;
        UserId = userId;
        CreatedAt = createdAt;
    }
}
