using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Identity.Application;

public sealed record RegisterRequest(
    string Email,
    string Password,
    string DisplayName,
    string? PreferredLanguage = null);

public sealed record LoginRequest(
    string Email,
    string Password);

public sealed record ForgotPasswordRequest(string Email);

public sealed record ResetPasswordRequest(
    string Email,
    string Code,
    string NewPassword);

public sealed record PasswordResetMessageResponse(string Message);

public sealed record RefreshTokenRequest(
    string RefreshToken);

public sealed record LogoutRequest(
    string RefreshToken);

public sealed record UpdateProfileRequest(
    string DisplayName,
    string PreferredLanguage,
    string? AvatarUrl = null);

public sealed record CreateAdminUserRequest(
    string Email,
    string Password,
    string DisplayName,
    UserRole Role,
    string? PreferredLanguage = null);

public sealed record UpdateUserStatusRequest(
    bool IsActive);

public sealed record UserProfileDto(
    Guid Id,
    string Email,
    string DisplayName,
    string? AvatarUrl,
    string Role,
    string PreferredLanguage,
    bool IsActive,
    DateTimeOffset CreatedAt);

public sealed record AuthTokenResponse(
    string AccessToken,
    string RefreshToken,
    DateTimeOffset ExpiresAt,
    UserProfileDto User);
