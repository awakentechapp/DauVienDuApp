using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Identity.Domain;

public sealed class RefreshToken : Entity<Guid>
{
    public Guid UserId { get; private set; }
    public string TokenHash { get; private set; } = string.Empty;
    public DateTimeOffset ExpiresAt { get; private set; }
    public DateTimeOffset? RevokedAt { get; private set; }
    public DateTimeOffset CreatedAt { get; private set; } = DateTimeOffset.UtcNow;

    public bool IsActive => RevokedAt == null && DateTimeOffset.UtcNow < ExpiresAt;

    private RefreshToken()
    {
    }

    public static RefreshToken Create(
        Guid userId,
        string tokenHash,
        DateTimeOffset expiresAt,
        DateTimeOffset? createdAt = null)
    {
        return new RefreshToken
        {
            Id = Guid.NewGuid(),
            UserId = userId,
            TokenHash = tokenHash,
            ExpiresAt = expiresAt,
            CreatedAt = createdAt ?? DateTimeOffset.UtcNow
        };
    }

    public void Revoke(DateTimeOffset now)
    {
        if (RevokedAt == null)
        {
            RevokedAt = now;
        }
    }
}
