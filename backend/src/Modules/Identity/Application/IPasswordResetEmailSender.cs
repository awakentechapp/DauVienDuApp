namespace SightseeingTravel.Modules.Identity.Application;

public interface IPasswordResetEmailSender
{
    bool IsConfigured { get; }

    Task SendResetCodeAsync(
        string email,
        string displayName,
        string code,
        string preferredLanguage,
        CancellationToken cancellationToken = default);
}
