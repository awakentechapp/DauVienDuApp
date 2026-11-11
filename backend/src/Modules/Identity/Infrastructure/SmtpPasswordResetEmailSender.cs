using System.Net;
using System.Net.Mail;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using SightseeingTravel.Modules.Identity.Application;

namespace SightseeingTravel.Modules.Identity.Infrastructure;

public sealed class SmtpPasswordResetEmailSender : IPasswordResetEmailSender
{
    private readonly IConfiguration _configuration;
    private readonly ILogger<SmtpPasswordResetEmailSender> _logger;

    public SmtpPasswordResetEmailSender(
        IConfiguration configuration,
        ILogger<SmtpPasswordResetEmailSender> logger)
    {
        _configuration = configuration;
        _logger = logger;
    }

    private string Host => _configuration["Email:SmtpHost"]?.Trim() ?? string.Empty;
    private string FromAddress => _configuration["Email:FromAddress"]?.Trim() ?? string.Empty;
    public bool IsConfigured => !string.IsNullOrWhiteSpace(Host)
        && int.TryParse(_configuration["Email:SmtpPort"], out var port)
        && port is > 0 and <= 65535
        && MailAddress.TryCreate(FromAddress, out _);

    public async Task SendResetCodeAsync(
        string email,
        string displayName,
        string code,
        string preferredLanguage,
        CancellationToken cancellationToken = default)
    {
        var port = _configuration.GetValue("Email:SmtpPort", 587);
        var username = _configuration["Email:SmtpUsername"];
        var password = _configuration["Email:SmtpPassword"];
        var fromName = _configuration["Email:FromName"] ?? "Dấu Viễn Du";
        var enableSsl = _configuration.GetValue("Email:EnableSsl", true);
        var isEnglish = preferredLanguage.StartsWith("en", StringComparison.OrdinalIgnoreCase);

        using var message = new MailMessage
        {
            From = new MailAddress(FromAddress, fromName),
            Subject = isEnglish ? "Your Dấu Viễn Du password reset code" : "Mã đặt lại mật khẩu Dấu Viễn Du",
            Body = isEnglish
                ? $"Hello {displayName},\n\nYour password reset code is: {code}\n\nIt expires in 15 minutes. If you did not request a reset, ignore this email. Do not share this code."
                : $"Xin chào {displayName},\n\nMã đặt lại mật khẩu của bạn là: {code}\n\nMã hết hạn sau 15 phút. Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này. Không chia sẻ mã cho người khác.",
            IsBodyHtml = false
        };
        message.To.Add(new MailAddress(email));

        using var client = new SmtpClient(Host, port)
        {
            EnableSsl = enableSsl,
            UseDefaultCredentials = false,
            DeliveryMethod = SmtpDeliveryMethod.Network,
            Timeout = 20_000
        };
        if (!string.IsNullOrWhiteSpace(username))
        {
            client.Credentials = new NetworkCredential(username, password ?? string.Empty);
        }

        try
        {
            await client.SendMailAsync(message, cancellationToken);
        }
        catch (Exception exception)
        {
            _logger.LogError(exception, "Password reset email delivery failed.");
            throw;
        }
    }
}
