using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.ArExperience.Domain;

public sealed class ArInfoCardLocalization : Entity<Guid>
{
    public Guid ArInfoCardId { get; set; }
    public string LanguageCode { get; set; } = SupportedLanguages.English;
    public string Title { get; set; } = string.Empty;
    public string? Subtitle { get; set; }
    public string Content { get; set; } = string.Empty;

    private ArInfoCardLocalization()
    {
    }

    public ArInfoCardLocalization(
        Guid id,
        Guid arInfoCardId,
        string languageCode,
        string title,
        string? subtitle,
        string content)
    {
        Id = id == Guid.Empty ? Guid.NewGuid() : id;
        ArInfoCardId = arInfoCardId;
        LanguageCode = SupportedLanguages.Normalize(languageCode);
        Title = title.Trim();
        Subtitle = string.IsNullOrWhiteSpace(subtitle) ? null : subtitle.Trim();
        Content = content.Trim();
    }

    public static ArInfoCardLocalization Create(
        Guid arInfoCardId,
        string languageCode,
        string title,
        string? subtitle,
        string content) =>
        new(Guid.NewGuid(), arInfoCardId, languageCode, title, subtitle, content);
}
