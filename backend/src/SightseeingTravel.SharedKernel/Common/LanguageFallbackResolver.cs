using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.SharedKernel.Common;

public static class LanguageFallbackResolver
{
    /// <summary>
    /// Selects the best matching localization according to Business Rule BR-03:
    /// Requested Language -> English ('en') -> Vietnamese ('vi') -> First Available.
    /// </summary>
    public static T? ResolveBestLocalization<T>(
        IEnumerable<T> localizations,
        Func<T, string> languageSelector,
        string? requestedLanguage)
    {
        var list = localizations.ToList();
        if (list.Count == 0)
            return default;

        var normalized = SupportedLanguages.Normalize(requestedLanguage);

        var exact = list.FirstOrDefault(x =>
            string.Equals(languageSelector(x), normalized, StringComparison.OrdinalIgnoreCase));
        if (exact is not null)
            return exact;

        var english = list.FirstOrDefault(x =>
            string.Equals(languageSelector(x), SupportedLanguages.English, StringComparison.OrdinalIgnoreCase));
        if (english is not null)
            return english;

        var vietnamese = list.FirstOrDefault(x =>
            string.Equals(languageSelector(x), SupportedLanguages.Vietnamese, StringComparison.OrdinalIgnoreCase));
        if (vietnamese is not null)
            return vietnamese;

        return list[0];
    }
}
