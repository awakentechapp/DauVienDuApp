using System.Security.Claims;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Api.Infrastructure;

public sealed class CurrentUser : ICurrentUser
{
    private readonly IHttpContextAccessor _httpContextAccessor;

    public CurrentUser(IHttpContextAccessor httpContextAccessor)
    {
        _httpContextAccessor = httpContextAccessor;
    }

    private ClaimsPrincipal? Principal => _httpContextAccessor.HttpContext?.User;

    public bool IsAuthenticated => Principal?.Identity?.IsAuthenticated == true;

    public Guid UserId
    {
        get
        {
            var sub = Principal?.FindFirstValue(ClaimTypes.NameIdentifier)
                      ?? Principal?.FindFirstValue("sub");
            return Guid.TryParse(sub, out var id) ? id : Guid.Empty;
        }
    }

    public string Email => Principal?.FindFirstValue(ClaimTypes.Email) ?? string.Empty;

    public string DisplayName => Principal?.FindFirstValue("display_name")
                                 ?? Principal?.FindFirstValue(ClaimTypes.Name)
                                 ?? string.Empty;

    public UserRole Role
    {
        get
        {
            var roleClaim = Principal?.FindFirstValue(ClaimTypes.Role)
                            ?? Principal?.FindFirstValue("role");
            return Enum.TryParse<UserRole>(roleClaim, ignoreCase: true, out var role)
                ? role
                : UserRole.Tourist;
        }
    }

    public string PreferredLanguage
    {
        get
        {
            var queryLang = _httpContextAccessor.HttpContext?.Request.Query["lang"].FirstOrDefault();
            if (!string.IsNullOrWhiteSpace(queryLang))
                return SupportedLanguages.Normalize(queryLang);

            var headerLang = _httpContextAccessor.HttpContext?.Request.Headers.AcceptLanguage.FirstOrDefault();
            if (!string.IsNullOrWhiteSpace(headerLang))
                return SupportedLanguages.Normalize(headerLang.Split(',')[0]);

            var claimLang = Principal?.FindFirstValue("preferred_language");
            return SupportedLanguages.Normalize(claimLang);
        }
    }
}

public sealed class SystemDateTimeProvider : IDateTimeProvider
{
    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}
