namespace SightseeingTravel.SharedKernel.Contracts;

public sealed record PoiGeofenceDto(
    Guid PoiId,
    string Code,
    string LocalizedName,
    double Latitude,
    double Longitude,
    double ActivationRadiusMeters,
    bool IsPublished);

public interface IAttractionGeofenceQueryService
{
    Task<PoiGeofenceDto?> GetPublishedPoiGeofenceAsync(
        Guid poiId,
        string? languageCode = null,
        CancellationToken cancellationToken = default);
}
