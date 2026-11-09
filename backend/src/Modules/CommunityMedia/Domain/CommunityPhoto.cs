using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.CommunityMedia.Domain;

public enum CommunityPhotoStatus
{
    Published = 1,
    Flagged = 2,
    RemovedByModerator = 3,
    DeletedByOwner = 4
}

public enum ReportResolutionStatus
{
    Pending = 1,
    ResolvedKeep = 2,
    ResolvedRemove = 3
}

public sealed class CommunityPhoto : AuditableEntity
{
    public const int AutoHideReportThreshold = 3;
    public const int MaxCaptionLength = 500;

    public Guid PoiId { get; private set; }
    public Guid UserId { get; private set; }
    public string AuthorDisplayName { get; private set; } = string.Empty;
    public string ObjectKey { get; private set; } = string.Empty;
    public string ImageUrl { get; private set; } = string.Empty;
    public string? Caption { get; private set; }
    public double CapturedLatitude { get; private set; }
    public double CapturedLongitude { get; private set; }
    public double DistanceFromPoiMeters { get; private set; }
    public double? AzimuthDegrees { get; private set; }
    public int LikeCount { get; private set; }
    public int ReportCount { get; private set; }
    public CommunityPhotoStatus Status { get; private set; } = CommunityPhotoStatus.Published;
    public Guid? ModeratedBy { get; private set; }
    public DateTimeOffset? ModeratedAt { get; private set; }
    public string? ModerationNote { get; private set; }

    public ICollection<PhotoLike> Likes { get; private set; } = new List<PhotoLike>();
    public ICollection<PhotoReport> Reports { get; private set; } = new List<PhotoReport>();

    private CommunityPhoto()
    {
    }

    public CommunityPhoto(
        Guid id,
        Guid poiId,
        Guid userId,
        string authorDisplayName,
        string objectKey,
        string imageUrl,
        string? caption,
        double capturedLatitude,
        double capturedLongitude,
        double distanceFromPoiMeters,
        double? azimuthDegrees,
        DateTimeOffset createdAt)
    {
        Id = id == Guid.Empty ? Guid.NewGuid() : id;
        PoiId = poiId;
        UserId = userId;
        AuthorDisplayName = string.IsNullOrWhiteSpace(authorDisplayName) ? "Traveler" : authorDisplayName.Trim();
        ObjectKey = objectKey;
        ImageUrl = imageUrl;
        Caption = string.IsNullOrWhiteSpace(caption) ? null : caption.Trim();
        CapturedLatitude = capturedLatitude;
        CapturedLongitude = capturedLongitude;
        DistanceFromPoiMeters = distanceFromPoiMeters;
        AzimuthDegrees = azimuthDegrees;
        LikeCount = 0;
        ReportCount = 0;
        Status = CommunityPhotoStatus.Published;
        CreatedAt = createdAt;
        UpdatedAt = createdAt;
    }

    public void IncrementLike()
    {
        LikeCount++;
    }

    public void DecrementLike()
    {
        if (LikeCount > 0)
        {
            LikeCount--;
        }
    }

    public PhotoReport RegisterReport(Guid reporterUserId, string reason, DateTimeOffset now)
    {
        var report = new PhotoReport(Id, reporterUserId, reason, now);
        Reports.Add(report);
        ReportCount++;
        UpdatedAt = now;

        if (ReportCount >= AutoHideReportThreshold && Status == CommunityPhotoStatus.Published)
        {
            Status = CommunityPhotoStatus.Flagged;
        }

        return report;
    }

    public void ModerateApprove(Guid moderatorId, string? note, DateTimeOffset now)
    {
        Status = CommunityPhotoStatus.Published;
        ModeratedBy = moderatorId;
        ModeratedAt = now;
        ModerationNote = string.IsNullOrWhiteSpace(note) ? null : note.Trim();
        UpdatedAt = now;

        foreach (var report in Reports.Where(r => r.Status == ReportResolutionStatus.Pending))
        {
            report.ResolveKeep(moderatorId, now);
        }
    }

    public void ModerateRemove(Guid moderatorId, string? note, DateTimeOffset now)
    {
        Status = CommunityPhotoStatus.RemovedByModerator;
        ModeratedBy = moderatorId;
        ModeratedAt = now;
        ModerationNote = string.IsNullOrWhiteSpace(note) ? null : note.Trim();
        UpdatedAt = now;

        foreach (var report in Reports.Where(r => r.Status == ReportResolutionStatus.Pending))
        {
            report.ResolveRemove(moderatorId, now);
        }
    }

    public void DeleteByOwner(DateTimeOffset now)
    {
        Status = CommunityPhotoStatus.DeletedByOwner;
        UpdatedAt = now;
    }
}
