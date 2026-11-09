using SightseeingTravel.SharedKernel.Common;

namespace SightseeingTravel.Modules.Attractions.Application;

public interface IAttractionService
{
    Task<Result<NearbyPoisResponse>> GetNearbyPoisAsync(
        double latitude,
        double longitude,
        double radiusKm = 10.0,
        string? languageCode = null,
        CancellationToken cancellationToken = default);

    Task<Result<PoiDetailDto>> GetPoiDetailAsync(
        Guid poiId,
        double? userLatitude = null,
        double? userLongitude = null,
        string? languageCode = null,
        CancellationToken cancellationToken = default);

    Task<Result<IReadOnlyList<AdminPoiDto>>> GetAdminPoisAsync(
        string? statusFilter = null,
        CancellationToken cancellationToken = default);

    Task<Result<AdminPoiDto>> CreatePoiAsync(
        CreatePoiRequest request,
        CancellationToken cancellationToken = default);

    Task<Result<AdminPoiDto>> UpdatePoiAsync(
        Guid poiId,
        UpdatePoiRequest request,
        CancellationToken cancellationToken = default);

    Task<Result<AdminPoiDto>> UploadCoverImageAsync(
        Guid poiId,
        Stream fileStream,
        long lengthBytes,
        string? contentType,
        CancellationToken cancellationToken = default);

    Task<Result<AdminPoiDto>> SubmitPoiForApprovalAsync(
        Guid poiId,
        CancellationToken cancellationToken = default);

    Task<Result<AdminPoiDto>> ApprovePoiAsync(
        Guid poiId,
        CancellationToken cancellationToken = default);

    Task<Result<AdminPoiDto>> RejectPoiAsync(
        Guid poiId,
        RejectPoiRequest request,
        CancellationToken cancellationToken = default);

    Task<Result> DeletePoiAsync(
        Guid poiId,
        CancellationToken cancellationToken = default);
}
