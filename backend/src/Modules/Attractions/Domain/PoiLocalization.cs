using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Attractions.Domain;

public sealed class PoiLocalization : Entity<Guid>
{
    public Guid PoiId { get; private set; }
    public string LanguageCode { get; private set; } = SupportedLanguages.English;
    public string Name { get; private set; } = string.Empty;
    public string ShortSummary { get; private set; } = string.Empty;
    public string CulturalStoryMarkdown { get; private set; } = string.Empty;
    public string? HistoricalPeriod { get; private set; }

    private PoiLocalization()
    {
    }

    public PoiLocalization(
        Guid id,
        Guid poiId,
        string languageCode,
        string name,
        string shortSummary,
        string culturalStoryMarkdown,
        string? historicalPeriod)
    {
        Id = id == Guid.Empty ? Guid.NewGuid() : id;
        PoiId = poiId;
        LanguageCode = SupportedLanguages.Normalize(languageCode);
        Name = name.Trim();
        ShortSummary = shortSummary.Trim();
        CulturalStoryMarkdown = culturalStoryMarkdown.Trim();
        HistoricalPeriod = string.IsNullOrWhiteSpace(historicalPeriod) ? null : historicalPeriod.Trim();
    }

    public void UpdateContent(
        string name,
        string shortSummary,
        string culturalStoryMarkdown,
        string? historicalPeriod)
    {
        Name = name.Trim();
        ShortSummary = shortSummary.Trim();
        CulturalStoryMarkdown = culturalStoryMarkdown.Trim();
        HistoricalPeriod = string.IsNullOrWhiteSpace(historicalPeriod) ? null : historicalPeriod.Trim();
    }
}
