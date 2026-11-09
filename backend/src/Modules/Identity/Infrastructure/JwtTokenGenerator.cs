using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using SightseeingTravel.Modules.Identity.Domain;

namespace SightseeingTravel.Modules.Identity.Infrastructure;

public sealed class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; set; } = "SightseeingTravel.Api";
    public string Audience { get; set; } = "SightseeingTravel.Client";
    public string SigningKey { get; set; } = string.Empty;
    public int AccessTokenExpirationMinutes { get; set; } = 60;
    public int RefreshTokenExpirationDays { get; set; } = 14;
}

public sealed class JwtTokenGenerator
{
    private readonly JwtOptions _options;

    public JwtTokenGenerator(IOptions<JwtOptions> options)
    {
        _options = options.Value;
    }

    public JwtTokenGenerator(JwtOptions options)
    {
        _options = options;
    }

    public JwtOptions Options => _options;

    public (string AccessToken, DateTimeOffset ExpiresAt) GenerateAccessToken(User user, DateTimeOffset? now = null)
    {
        var issuedAt = now ?? DateTimeOffset.UtcNow;
        var expiresAt = issuedAt.AddMinutes(_options.AccessTokenExpirationMinutes);

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
            new(JwtRegisteredClaimNames.Email, user.Email),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
            new("display_name", user.DisplayName),
            new("role", user.Role.ToString()),
            new(ClaimTypes.Role, user.Role.ToString()),
            new("preferred_language", user.PreferredLanguage)
        };

        var keyBytes = Encoding.UTF8.GetBytes(_options.SigningKey);
        var securityKey = new SymmetricSecurityKey(keyBytes);
        var credentials = new SigningCredentials(securityKey, SecurityAlgorithms.HmacSha256);

        var tokenDescriptor = new JwtSecurityToken(
            issuer: _options.Issuer,
            audience: _options.Audience,
            claims: claims,
            notBefore: issuedAt.UtcDateTime,
            expires: expiresAt.UtcDateTime,
            signingCredentials: credentials);

        var tokenString = new JwtSecurityTokenHandler().WriteToken(tokenDescriptor);
        return (tokenString, expiresAt);
    }

    public (string RawToken, string TokenHash, DateTimeOffset ExpiresAt) GenerateRefreshToken(DateTimeOffset? now = null)
    {
        var issuedAt = now ?? DateTimeOffset.UtcNow;
        var expiresAt = issuedAt.AddDays(_options.RefreshTokenExpirationDays);
        var rawToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var tokenHash = ComputeTokenHash(rawToken);
        return (rawToken, tokenHash, expiresAt);
    }

    public static string ComputeTokenHash(string rawToken)
    {
        var bytes = Encoding.UTF8.GetBytes(rawToken.Trim());
        var hash = SHA256.HashData(bytes);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }
}
