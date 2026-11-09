using SightseeingTravel.SharedKernel.Common;

namespace SightseeingTravel.Modules.Identity.Application;

public interface IIdentityService
{
    Task<Result<AuthTokenResponse>> RegisterTouristAsync(RegisterRequest request, CancellationToken ct = default);
    Task<Result<AuthTokenResponse>> LoginAsync(LoginRequest request, CancellationToken ct = default);
    Task<Result<AuthTokenResponse>> RefreshTokenAsync(RefreshTokenRequest request, CancellationToken ct = default);
    Task<Result> LogoutAsync(LogoutRequest request, CancellationToken ct = default);
    Task<Result<UserProfileDto>> GetCurrentUserProfileAsync(CancellationToken ct = default);
    Task<Result<UserProfileDto>> UpdateProfileAsync(UpdateProfileRequest request, CancellationToken ct = default);
    Task<Result<UserProfileDto>> CreateStaffUserAsync(CreateAdminUserRequest request, CancellationToken ct = default);
    Task<Result<UserProfileDto>> SetUserActiveStatusAsync(Guid userId, UpdateUserStatusRequest request, CancellationToken ct = default);
    Task<Result<IReadOnlyList<UserProfileDto>>> GetAllUsersAsync(CancellationToken ct = default);
}
