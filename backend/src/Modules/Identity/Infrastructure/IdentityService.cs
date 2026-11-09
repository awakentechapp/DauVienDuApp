using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.Identity.Application;
using SightseeingTravel.Modules.Identity.Domain;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Identity.Infrastructure;

public sealed class IdentityService : IIdentityService
{
    private const int MinPasswordLength = 8;

    private readonly IdentityDbContext _dbContext;
    private readonly PasswordHasher _passwordHasher;
    private readonly JwtTokenGenerator _jwtTokenGenerator;
    private readonly ICurrentUser _currentUser;
    private readonly IDateTimeProvider _dateTimeProvider;

    public IdentityService(
        IdentityDbContext dbContext,
        PasswordHasher passwordHasher,
        JwtTokenGenerator jwtTokenGenerator,
        ICurrentUser currentUser,
        IDateTimeProvider dateTimeProvider)
    {
        _dbContext = dbContext;
        _passwordHasher = passwordHasher;
        _jwtTokenGenerator = jwtTokenGenerator;
        _currentUser = currentUser;
        _dateTimeProvider = dateTimeProvider;
    }

    public async Task<Result<AuthTokenResponse>> RegisterTouristAsync(
        RegisterRequest request,
        CancellationToken ct = default)
    {
        var validationError = ValidateRegistrationInputs(request.Email, request.Password, request.DisplayName);
        if (validationError is not null)
            return Result.Failure<AuthTokenResponse>(validationError);

        var normalizedEmail = User.NormalizeEmail(request.Email);
        var emailExists = await _dbContext.Users
            .AnyAsync(u => u.Email == normalizedEmail, ct);

        if (emailExists)
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthEmailAlreadyExists,
                "An account with this email address already exists.",
                ErrorType.Conflict));
        }

        var passwordHash = _passwordHasher.HashPassword(request.Password);
        var user = User.CreateTourist(
            normalizedEmail,
            passwordHash,
            request.DisplayName,
            request.PreferredLanguage);

        _dbContext.Users.Add(user);

        var response = await IssueTokensAsync(user, ct);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(response);
    }

    public async Task<Result<AuthTokenResponse>> LoginAsync(
        LoginRequest request,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(request.Email) || string.IsNullOrWhiteSpace(request.Password))
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthInvalidCredentials,
                "Email and password are required.",
                ErrorType.Unauthorized));
        }

        var normalizedEmail = User.NormalizeEmail(request.Email);
        var user = await _dbContext.Users
            .FirstOrDefaultAsync(u => u.Email == normalizedEmail, ct);

        if (user is null || !_passwordHasher.VerifyPassword(request.Password, user.PasswordHash))
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthInvalidCredentials,
                "Invalid email or password.",
                ErrorType.Unauthorized));
        }

        if (!user.IsActive)
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthAccountLocked,
                "This user account has been locked or deactivated.",
                ErrorType.Forbidden));
        }

        var response = await IssueTokensAsync(user, ct);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(response);
    }

    public async Task<Result<AuthTokenResponse>> RefreshTokenAsync(
        RefreshTokenRequest request,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(request.RefreshToken))
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthInvalidRefreshToken,
                "Refresh token is required.",
                ErrorType.Unauthorized));
        }

        var now = _dateTimeProvider.UtcNow;
        var tokenHash = JwtTokenGenerator.ComputeTokenHash(request.RefreshToken);

        var storedToken = await _dbContext.RefreshTokens
            .FirstOrDefaultAsync(rt => rt.TokenHash == tokenHash, ct);

        if (storedToken is null || storedToken.RevokedAt != null || now >= storedToken.ExpiresAt)
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthInvalidRefreshToken,
                "Refresh token is invalid, expired, or already revoked.",
                ErrorType.Unauthorized));
        }

        var user = await _dbContext.Users
            .FirstOrDefaultAsync(u => u.Id == storedToken.UserId, ct);

        if (user is null)
        {
            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthInvalidRefreshToken,
                "Associated user was not found.",
                ErrorType.Unauthorized));
        }

        if (!user.IsActive)
        {
            storedToken.Revoke(now);
            await _dbContext.SaveChangesAsync(ct);

            return Result.Failure<AuthTokenResponse>(new Error(
                ErrorCodes.AuthAccountLocked,
                "This user account has been locked or deactivated.",
                ErrorType.Forbidden));
        }

        storedToken.Revoke(now);
        var response = await IssueTokensAsync(user, ct);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(response);
    }

    public async Task<Result> LogoutAsync(
        LogoutRequest request,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(request.RefreshToken))
            return Result.Success();

        var tokenHash = JwtTokenGenerator.ComputeTokenHash(request.RefreshToken);
        var storedToken = await _dbContext.RefreshTokens
            .FirstOrDefaultAsync(rt => rt.TokenHash == tokenHash, ct);

        if (storedToken is not null && storedToken.RevokedAt == null)
        {
            storedToken.Revoke(_dateTimeProvider.UtcNow);
            await _dbContext.SaveChangesAsync(ct);
        }

        return Result.Success();
    }

    public async Task<Result<UserProfileDto>> GetCurrentUserProfileAsync(CancellationToken ct = default)
    {
        if (!_currentUser.IsAuthenticated || _currentUser.UserId == Guid.Empty)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthInvalidCredentials,
                "User is not authenticated.",
                ErrorType.Unauthorized));
        }

        var user = await _dbContext.Users
            .AsNoTracking()
            .FirstOrDefaultAsync(u => u.Id == _currentUser.UserId, ct);

        if (user is null)
        {
            return Result.Failure<UserProfileDto>(new Error(
                "USER_NOT_FOUND",
                "User account was not found.",
                ErrorType.NotFound));
        }

        if (!user.IsActive)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthAccountLocked,
                "This user account has been locked or deactivated.",
                ErrorType.Forbidden));
        }

        return Result.Success(MapToDto(user));
    }

    public async Task<Result<UserProfileDto>> UpdateProfileAsync(
        UpdateProfileRequest request,
        CancellationToken ct = default)
    {
        if (!_currentUser.IsAuthenticated || _currentUser.UserId == Guid.Empty)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthInvalidCredentials,
                "User is not authenticated.",
                ErrorType.Unauthorized));
        }

        if (string.IsNullOrWhiteSpace(request.DisplayName))
        {
            return Result.Failure<UserProfileDto>(new Error(
                "VALIDATION_ERROR",
                "Display name cannot be empty.",
                ErrorType.Validation));
        }

        var user = await _dbContext.Users
            .FirstOrDefaultAsync(u => u.Id == _currentUser.UserId, ct);

        if (user is null)
        {
            return Result.Failure<UserProfileDto>(new Error(
                "USER_NOT_FOUND",
                "User account was not found.",
                ErrorType.NotFound));
        }

        if (!user.IsActive)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthAccountLocked,
                "This user account has been locked or deactivated.",
                ErrorType.Forbidden));
        }

        user.UpdateProfile(request.DisplayName, request.PreferredLanguage, request.AvatarUrl);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToDto(user));
    }

    public async Task<Result<UserProfileDto>> CreateStaffUserAsync(
        CreateAdminUserRequest request,
        CancellationToken ct = default)
    {
        if (!_currentUser.IsSuperAdmin)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only SuperAdmin can create staff accounts.",
                ErrorType.Forbidden));
        }

        var validationError = ValidateRegistrationInputs(request.Email, request.Password, request.DisplayName);
        if (validationError is not null)
            return Result.Failure<UserProfileDto>(validationError);

        if (!Enum.IsDefined(typeof(UserRole), request.Role))
        {
            return Result.Failure<UserProfileDto>(new Error(
                "VALIDATION_ERROR",
                "Specified user role is invalid.",
                ErrorType.Validation));
        }

        var normalizedEmail = User.NormalizeEmail(request.Email);
        var emailExists = await _dbContext.Users
            .AnyAsync(u => u.Email == normalizedEmail, ct);

        if (emailExists)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthEmailAlreadyExists,
                "An account with this email address already exists.",
                ErrorType.Conflict));
        }

        var passwordHash = _passwordHasher.HashPassword(request.Password);
        var staffUser = User.CreateStaff(
            normalizedEmail,
            passwordHash,
            request.DisplayName,
            request.Role,
            request.PreferredLanguage);

        _dbContext.Users.Add(staffUser);
        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToDto(staffUser));
    }

    public async Task<Result<UserProfileDto>> SetUserActiveStatusAsync(
        Guid userId,
        UpdateUserStatusRequest request,
        CancellationToken ct = default)
    {
        if (!_currentUser.IsSuperAdmin)
        {
            return Result.Failure<UserProfileDto>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only SuperAdmin can update user account status.",
                ErrorType.Forbidden));
        }

        var user = await _dbContext.Users
            .FirstOrDefaultAsync(u => u.Id == userId, ct);

        if (user is null)
        {
            return Result.Failure<UserProfileDto>(new Error(
                "USER_NOT_FOUND",
                "Target user account was not found.",
                ErrorType.NotFound));
        }

        user.SetActiveStatus(request.IsActive);

        if (!request.IsActive)
        {
            var now = _dateTimeProvider.UtcNow;
            var activeTokens = await _dbContext.RefreshTokens
                .Where(rt => rt.UserId == userId && rt.RevokedAt == null)
                .ToListAsync(ct);

            foreach (var token in activeTokens)
            {
                token.Revoke(now);
            }
        }

        await _dbContext.SaveChangesAsync(ct);

        return Result.Success(MapToDto(user));
    }

    public async Task<Result<IReadOnlyList<UserProfileDto>>> GetAllUsersAsync(CancellationToken ct = default)
    {
        if (!_currentUser.IsSuperAdmin)
        {
            return Result.Failure<IReadOnlyList<UserProfileDto>>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only SuperAdmin can list user accounts.",
                ErrorType.Forbidden));
        }

        var users = await _dbContext.Users
            .AsNoTracking()
            .OrderByDescending(u => u.CreatedAt)
            .ToListAsync(ct);

        var dtos = users.Select(MapToDto).ToList();
        return Result.Success<IReadOnlyList<UserProfileDto>>(dtos);
    }

    private Task<AuthTokenResponse> IssueTokensAsync(User user, CancellationToken ct)
    {
        var now = _dateTimeProvider.UtcNow;
        var (accessToken, accessExpiresAt) = _jwtTokenGenerator.GenerateAccessToken(user, now);
        var (rawRefreshToken, refreshTokenHash, refreshExpiresAt) = _jwtTokenGenerator.GenerateRefreshToken(now);

        var refreshToken = RefreshToken.Create(
            user.Id,
            refreshTokenHash,
            refreshExpiresAt,
            now);

        _dbContext.RefreshTokens.Add(refreshToken);

        var response = new AuthTokenResponse(
            accessToken,
            rawRefreshToken,
            accessExpiresAt,
            MapToDto(user));

        return Task.FromResult(response);
    }

    private static Error? ValidateRegistrationInputs(string email, string password, string displayName)
    {
        if (string.IsNullOrWhiteSpace(email) || !email.Contains('@'))
        {
            return new Error(
                "VALIDATION_ERROR",
                "A valid email address is required.",
                ErrorType.Validation);
        }

        if (string.IsNullOrWhiteSpace(password) || password.Length < MinPasswordLength)
        {
            return new Error(
                "VALIDATION_ERROR",
                $"Password must be at least {MinPasswordLength} characters long.",
                ErrorType.Validation);
        }

        if (string.IsNullOrWhiteSpace(displayName))
        {
            return new Error(
                "VALIDATION_ERROR",
                "Display name is required.",
                ErrorType.Validation);
        }

        return null;
    }

    public static UserProfileDto MapToDto(User user) =>
        new(
            user.Id,
            user.Email,
            user.DisplayName,
            user.AvatarUrl,
            user.Role.ToString(),
            user.PreferredLanguage,
            user.IsActive,
            user.CreatedAt);
}
