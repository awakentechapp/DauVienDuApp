using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Identity.Domain;

public sealed class User : AuditableEntity
{
    public string Email { get; private set; } = string.Empty;
    public string PasswordHash { get; private set; } = string.Empty;
    public string DisplayName { get; private set; } = string.Empty;
    public string? AvatarUrl { get; private set; }
    public UserRole Role { get; private set; } = UserRole.Tourist;
    public string PreferredLanguage { get; private set; } = SupportedLanguages.English;
    public bool IsActive { get; private set; } = true;

    private readonly List<RefreshToken> _refreshTokens = new();
    public ICollection<RefreshToken> RefreshTokens => _refreshTokens;

    private User()
    {
    }

    public static User CreateTourist(
        string email,
        string passwordHash,
        string displayName,
        string? preferredLanguage = null)
    {
        var now = DateTimeOffset.UtcNow;
        return new User
        {
            Id = Guid.NewGuid(),
            Email = NormalizeEmail(email),
            PasswordHash = passwordHash,
            DisplayName = displayName.Trim(),
            Role = UserRole.Tourist,
            PreferredLanguage = SupportedLanguages.Normalize(preferredLanguage),
            IsActive = true,
            CreatedAt = now,
            UpdatedAt = now
        };
    }

    public static User CreateStaff(
        string email,
        string passwordHash,
        string displayName,
        UserRole role,
        string? preferredLanguage = null)
    {
        var now = DateTimeOffset.UtcNow;
        return new User
        {
            Id = Guid.NewGuid(),
            Email = NormalizeEmail(email),
            PasswordHash = passwordHash,
            DisplayName = displayName.Trim(),
            Role = role,
            PreferredLanguage = SupportedLanguages.Normalize(preferredLanguage),
            IsActive = true,
            CreatedAt = now,
            UpdatedAt = now
        };
    }

    public void UpdateProfile(string displayName, string preferredLanguage, string? avatarUrl)
    {
        DisplayName = displayName.Trim();
        PreferredLanguage = SupportedLanguages.Normalize(preferredLanguage);
        AvatarUrl = string.IsNullOrWhiteSpace(avatarUrl) ? null : avatarUrl.Trim();
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void SetActiveStatus(bool isActive)
    {
        IsActive = isActive;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static string NormalizeEmail(string email) =>
        email.Trim().ToLowerInvariant();
}
