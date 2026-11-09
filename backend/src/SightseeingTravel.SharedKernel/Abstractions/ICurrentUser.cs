using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.SharedKernel.Abstractions;

public interface ICurrentUser
{
    Guid UserId { get; }
    string Email { get; }
    string DisplayName { get; }
    UserRole Role { get; }
    string PreferredLanguage { get; }
    bool IsAuthenticated { get; }
    bool IsAdminOrSuperAdmin => IsAuthenticated && (Role == UserRole.Admin || Role == UserRole.SuperAdmin);
    bool IsSuperAdmin => IsAuthenticated && Role == UserRole.SuperAdmin;
}

public interface IDateTimeProvider
{
    DateTimeOffset UtcNow { get; }
}
