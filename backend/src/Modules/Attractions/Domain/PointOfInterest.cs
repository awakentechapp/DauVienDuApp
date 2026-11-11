using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Attractions.Domain;

public enum PoiStatus
{
    Draft = 1,
    PendingApproval = 2,
    Published = 3,
    Rejected = 4,
    Hidden = 5
}

public sealed class PointOfInterest : AuditableEntity
{
    public const double DefaultActivationRadiusMeters = 100.0;
    public const double MinActivationRadiusMeters = 10.0;
    public const double MaxActivationRadiusMeters = 2000.0;

    public string Code { get; private set; } = string.Empty;
    public string Category { get; private set; } = PoiCategories.HistoricalSite;
    public double Latitude { get; private set; }
    public double Longitude { get; private set; }
    public double ActivationRadiusMeters { get; private set; } = DefaultActivationRadiusMeters;
    public string? CoverImageUrl { get; private set; }
    public string? CoverObjectKey { get; private set; }
    public PoiStatus Status { get; private set; } = PoiStatus.Draft;
    public string? RejectionReason { get; private set; }
    public Guid CreatedBy { get; private set; }
    public Guid? ApprovedBy { get; private set; }
    public DateTimeOffset? ApprovedAt { get; private set; }

    public List<PoiLocalization> Localizations { get; private set; } = new();

    private PointOfInterest()
    {
    }

    public static Result<PointOfInterest> Create(
        string code,
        string? category,
        double latitude,
        double longitude,
        double activationRadiusMeters,
        string? coverImageUrl,
        Guid createdBy,
        UserRole creatorRole,
        bool saveAsDraft,
        DateTimeOffset now)
    {
        if (creatorRole is not (UserRole.Admin or UserRole.SuperAdmin))
        {
            return Result.Failure<PointOfInterest>(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only Admin or SuperAdmin can create a Point of Interest.",
                ErrorType.Forbidden));
        }

        if (string.IsNullOrWhiteSpace(code))
        {
            return Result.Failure<PointOfInterest>(new Error(
                ErrorCodes.PoiInvalidStateTransition,
                "POI code is required.",
                ErrorType.Validation));
        }

        if (!PoiCategories.TryNormalize(category ?? PoiCategories.HistoricalSite, out var normalizedCategory))
        {
            return Result.Failure<PointOfInterest>(new Error(
                "POI_INVALID_CATEGORY",
                "Category must be one of the supported POI categories.",
                ErrorType.Validation));
        }

        if (!GeoCalculator.IsValidCoordinate(latitude, longitude))
        {
            return Result.Failure<PointOfInterest>(new Error(
                "POI_INVALID_COORDINATES",
                "Latitude must be between -90 and 90, and Longitude must be between -180 and 180.",
                ErrorType.Validation));
        }

        var radius = activationRadiusMeters <= 0 ? DefaultActivationRadiusMeters : activationRadiusMeters;
        if (radius is < MinActivationRadiusMeters or > MaxActivationRadiusMeters)
        {
            return Result.Failure<PointOfInterest>(new Error(
                "POI_INVALID_ACTIVATION_RADIUS",
                $"Activation radius must be between {MinActivationRadiusMeters} and {MaxActivationRadiusMeters} meters.",
                ErrorType.Validation));
        }

        PoiStatus initialStatus;
        Guid? approvedBy = null;
        DateTimeOffset? approvedAt = null;

        if (saveAsDraft)
        {
            initialStatus = PoiStatus.Draft;
        }
        else if (creatorRole == UserRole.SuperAdmin)
        {
            initialStatus = PoiStatus.Published;
            approvedBy = createdBy;
            approvedAt = now;
        }
        else
        {
            initialStatus = PoiStatus.PendingApproval;
        }

        var poi = new PointOfInterest
        {
            Id = Guid.NewGuid(),
            Code = NormalizeCode(code),
            Category = normalizedCategory,
            Latitude = latitude,
            Longitude = longitude,
            ActivationRadiusMeters = radius,
            CoverImageUrl = string.IsNullOrWhiteSpace(coverImageUrl) ? null : coverImageUrl.Trim(),
            Status = initialStatus,
            CreatedBy = createdBy,
            ApprovedBy = approvedBy,
            ApprovedAt = approvedAt,
            CreatedAt = now,
            UpdatedAt = now
        };

        return Result.Success(poi);
    }

    public Result UpdateDetails(
        string? category,
        double latitude,
        double longitude,
        double activationRadiusMeters,
        string? coverImageUrl,
        UserRole editorRole,
        Guid editorId,
        DateTimeOffset now)
    {
        if (editorRole is not (UserRole.Admin or UserRole.SuperAdmin))
        {
            return Result.Failure(new Error(
                ErrorCodes.AuthForbiddenRole,
                "Only Admin or SuperAdmin can update a Point of Interest.",
                ErrorType.Forbidden));
        }

        if (!GeoCalculator.IsValidCoordinate(latitude, longitude))
        {
            return Result.Failure(new Error(
                "POI_INVALID_COORDINATES",
                "Latitude must be between -90 and 90, and Longitude must be between -180 and 180.",
                ErrorType.Validation));
        }

        var normalizedCategory = Category;
        if (!string.IsNullOrWhiteSpace(category) && !PoiCategories.TryNormalize(category, out normalizedCategory))
        {
            return Result.Failure(new Error(
                "POI_INVALID_CATEGORY",
                "Category must be one of the supported POI categories.",
                ErrorType.Validation));
        }

        var radius = activationRadiusMeters <= 0 ? DefaultActivationRadiusMeters : activationRadiusMeters;
        if (radius is < MinActivationRadiusMeters or > MaxActivationRadiusMeters)
        {
            return Result.Failure(new Error(
                "POI_INVALID_ACTIVATION_RADIUS",
                $"Activation radius must be between {MinActivationRadiusMeters} and {MaxActivationRadiusMeters} meters.",
                ErrorType.Validation));
        }

        Latitude = latitude;
        Longitude = longitude;
        Category = normalizedCategory;
        ActivationRadiusMeters = radius;

        if (!string.IsNullOrWhiteSpace(coverImageUrl))
        {
            CoverImageUrl = coverImageUrl.Trim();
        }

        if (Status == PoiStatus.Published)
        {
            if (editorRole == UserRole.Admin)
            {
                Status = PoiStatus.PendingApproval;
                ApprovedBy = null;
                ApprovedAt = null;
            }
            else if (editorRole == UserRole.SuperAdmin)
            {
                ApprovedBy = editorId;
                ApprovedAt = now;
            }
        }

        UpdatedAt = now;
        return Result.Success();
    }

    public Result SubmitForApproval(DateTimeOffset now)
    {
        if (Status is not (PoiStatus.Draft or PoiStatus.Rejected or PoiStatus.PendingApproval))
        {
            return Result.Failure(new Error(
                ErrorCodes.PoiInvalidStateTransition,
                $"Cannot submit POI for approval from status '{Status}'. Only Draft or Rejected POIs can be submitted.",
                ErrorType.Conflict));
        }

        Status = PoiStatus.PendingApproval;
        RejectionReason = null;
        UpdatedAt = now;
        return Result.Success();
    }

    public Result Approve(Guid superAdminId, DateTimeOffset now)
    {
        if (Status is not (PoiStatus.PendingApproval or PoiStatus.Draft or PoiStatus.Hidden))
        {
            return Result.Failure(new Error(
                ErrorCodes.PoiInvalidStateTransition,
                $"Cannot approve POI when its current status is '{Status}'.",
                ErrorType.Conflict));
        }

        Status = PoiStatus.Published;
        RejectionReason = null;
        ApprovedBy = superAdminId;
        ApprovedAt = now;
        UpdatedAt = now;
        return Result.Success();
    }

    public Result Reject(Guid superAdminId, string? reason, DateTimeOffset now)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            return Result.Failure(new Error(
                ErrorCodes.PoiRejectReasonRequired,
                "A rejection reason is required when rejecting a POI.",
                ErrorType.Validation));
        }

        if (Status is not (PoiStatus.PendingApproval or PoiStatus.Draft or PoiStatus.Published))
        {
            return Result.Failure(new Error(
                ErrorCodes.PoiInvalidStateTransition,
                $"Cannot reject POI when its current status is '{Status}'.",
                ErrorType.Conflict));
        }

        Status = PoiStatus.Rejected;
        RejectionReason = reason.Trim();
        ApprovedBy = null;
        ApprovedAt = null;
        UpdatedAt = now;
        return Result.Success();
    }

    public Result Hide(DateTimeOffset now)
    {
        Status = PoiStatus.Hidden;
        UpdatedAt = now;
        return Result.Success();
    }

    public void SetCoverImage(string coverImageUrl, string coverObjectKey, DateTimeOffset now)
    {
        CoverImageUrl = coverImageUrl;
        CoverObjectKey = coverObjectKey;
        UpdatedAt = now;
    }

    public static string NormalizeCode(string code) =>
        code.Trim().ToUpperInvariant().Replace(' ', '-');
}
