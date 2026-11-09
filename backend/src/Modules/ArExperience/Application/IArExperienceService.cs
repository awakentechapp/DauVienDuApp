using SightseeingTravel.SharedKernel.Common;

namespace SightseeingTravel.Modules.ArExperience.Application;

public interface IArExperienceService
{
    Task<Result<ArContextResponse>> GetArContextForPoiAsync(
        Guid poiId,
        double userLatitude,
        double userLongitude,
        string? languageCode = null,
        CancellationToken ct = default);

    Task<Result<IReadOnlyList<AdminArInfoCardDto>>> GetArCardsByPoiForAdminAsync(
        Guid poiId,
        CancellationToken ct = default);

    Task<Result<AdminArInfoCardDto>> CreateArCardAsync(
        Guid poiId,
        UpsertArInfoCardRequest request,
        CancellationToken ct = default);

    Task<Result<AdminArInfoCardDto>> UpdateArCardAsync(
        Guid cardId,
        UpsertArInfoCardRequest request,
        CancellationToken ct = default);

    Task<Result> DeleteArCardAsync(
        Guid cardId,
        CancellationToken ct = default);
}
