using SightseeingTravel.SharedKernel.Common;

namespace SightseeingTravel.Modules.CommunityMedia.Application;

public interface ICommunityMediaService
{
    Task<Result<PagedResponse<CommunityPhotoDto>>> GetPoiPhotosAsync(
        Guid poiId,
        string sortBy,
        int page,
        int pageSize,
        CancellationToken ct = default);

    Task<Result<CommunityPhotoDto>> UploadPhotoAsync(
        UploadPhotoCommand command,
        CancellationToken ct = default);

    Task<Result<ToggleLikeResponse>> ToggleLikeAsync(
        Guid photoId,
        CancellationToken ct = default);

    Task<Result> ReportPhotoAsync(
        Guid photoId,
        string reason,
        CancellationToken ct = default);

    Task<Result> DeleteOwnPhotoAsync(
        Guid photoId,
        CancellationToken ct = default);

    Task<Result<PagedResponse<CommunityPhotoDto>>> GetMyPhotosAsync(
        int page,
        int pageSize,
        CancellationToken ct = default);

    Task<Result<PagedResponse<AdminReportedPhotoDto>>> GetModerationQueueAsync(
        int page,
        int pageSize,
        CancellationToken ct = default);

    Task<Result<AdminReportedPhotoDto>> ModeratePhotoAsync(
        Guid photoId,
        ModerationDecisionRequest request,
        CancellationToken ct = default);
}
