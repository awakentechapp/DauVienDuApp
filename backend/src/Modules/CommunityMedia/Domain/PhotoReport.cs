using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.CommunityMedia.Domain;

public sealed class PhotoReport : Entity<Guid>
{
    public Guid PhotoId { get; private set; }
    public Guid ReporterUserId { get; private set; }
    public string Reason { get; private set; } = string.Empty;
    public ReportResolutionStatus Status { get; private set; } = ReportResolutionStatus.Pending;
    public Guid? ResolvedBy { get; private set; }
    public DateTimeOffset? ResolvedAt { get; private set; }
    public DateTimeOffset CreatedAt { get; private set; }

    public CommunityPhoto? Photo { get; private set; }

    private PhotoReport()
    {
    }

    public PhotoReport(Guid photoId, Guid reporterUserId, string reason, DateTimeOffset createdAt)
    {
        Id = Guid.NewGuid();
        PhotoId = photoId;
        ReporterUserId = reporterUserId;
        Reason = reason.Trim();
        Status = ReportResolutionStatus.Pending;
        CreatedAt = createdAt;
    }

    public void ResolveKeep(Guid moderatorId, DateTimeOffset now)
    {
        Status = ReportResolutionStatus.ResolvedKeep;
        ResolvedBy = moderatorId;
        ResolvedAt = now;
    }

    public void ResolveRemove(Guid moderatorId, DateTimeOffset now)
    {
        Status = ReportResolutionStatus.ResolvedRemove;
        ResolvedBy = moderatorId;
        ResolvedAt = now;
    }
}
