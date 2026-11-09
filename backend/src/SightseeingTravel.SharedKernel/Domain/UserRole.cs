namespace SightseeingTravel.SharedKernel.Domain;

public enum UserRole
{
    Tourist = 1,
    Admin = 2,
    SuperAdmin = 3
}

public static class AuthPolicies
{
    public const string AuthenticatedUser = "AuthenticatedUser";
    public const string AdminOrSuperAdmin = "AdminOrSuperAdmin";
    public const string SuperAdminOnly = "SuperAdminOnly";
}

public static class SupportedLanguages
{
    public const string Vietnamese = "vi";
    public const string English = "en";

    public static readonly IReadOnlyList<string> All = new[] { Vietnamese, English };

    public static string Normalize(string? lang)
    {
        if (string.IsNullOrWhiteSpace(lang))
            return English;

        var code = lang.Trim().ToLowerInvariant();
        if (code.StartsWith("vi"))
            return Vietnamese;
        if (code.StartsWith("en"))
            return English;

        return code.Length > 2 ? code[..2] : code;
    }
}
