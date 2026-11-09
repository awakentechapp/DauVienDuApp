using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.Attractions.Domain;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.Attractions.Infrastructure;

public sealed class AttractionGeofenceQueryService : IAttractionGeofenceQueryService
{
    private readonly AttractionsDbContext _dbContext;

    public AttractionGeofenceQueryService(AttractionsDbContext dbContext)
    {
        _dbContext = dbContext;
    }

    public async Task<PoiGeofenceDto?> GetPublishedPoiGeofenceAsync(
        Guid poiId,
        string? languageCode = null,
        CancellationToken cancellationToken = default)
    {
        var poi = await _dbContext.PointsOfInterest
            .AsNoTracking()
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(
                x => x.Id == poiId && x.Status == PoiStatus.Published,
                cancellationToken);

        if (poi is null)
        {
            return null;
        }

        var localization = LanguageFallbackResolver.ResolveBestLocalization(
            poi.Localizations,
            x => x.LanguageCode,
            languageCode);

        return new PoiGeofenceDto(
            poi.Id,
            poi.Code,
            localization?.Name ?? poi.Code,
            poi.Latitude,
            poi.Longitude,
            poi.ActivationRadiusMeters,
            poi.Status == PoiStatus.Published);
    }
}
