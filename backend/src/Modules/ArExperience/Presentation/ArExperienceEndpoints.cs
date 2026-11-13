using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Routing;
using SightseeingTravel.Modules.ArExperience.Application;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.ArExperience.Presentation;

public static class ArExperienceEndpoints
{
    public static IEndpointRouteBuilder MapArExperienceEndpoints(IEndpointRouteBuilder app)
    {
        // Tourist AR Context Endpoint
        app.MapGet("/api/v1/pois/{poiId:guid}/ar-context", async (
                Guid poiId,
                [FromQuery] double? lat,
                [FromQuery] double? lng,
                [FromQuery] double? latitude,
                [FromQuery] double? longitude,
                [FromQuery] string? lang,
                IArExperienceService arExperienceService,
                CancellationToken ct) =>
            {
                var resolvedLat = lat ?? latitude;
                var resolvedLng = lng ?? longitude;

                if (resolvedLat is null || resolvedLng is null)
                {
                    return ToErrorResult(new Error(
                        "INVALID_COORDINATES",
                        "Query parameters 'lat' and 'lng' are required to verify the POI geofence.",
                        ErrorType.Validation));
                }

                var result = await arExperienceService.GetArContextForPoiAsync(
                    poiId,
                    resolvedLat.Value,
                    resolvedLng.Value,
                    lang,
                    ct);

                return result.IsSuccess
                    ? Results.Ok(result.Value)
                    : ToErrorResult(result.Error);
            })
            .RequireAuthorization(AuthPolicies.AuthenticatedUser)
            .WithTags("AR Experience");

        // Admin & SuperAdmin AR Info Card Management Endpoints
        var adminGroup = app.MapGroup("/api/v1/admin")
            .RequireAuthorization(AuthPolicies.AdminOrSuperAdmin)
            .WithTags("Admin - AR Experience");

        adminGroup.MapGet("/ar-models", async (
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.GetArModelLibraryAsync(ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        });

        adminGroup.MapGet("/ar-models/catalog", async (
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.GetArModelCatalogAsync(ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        });

        adminGroup.MapPost("/pois/{poiId:guid}/placed-ar-model", async (
            Guid poiId,
            [FromBody] PlaceArModelRequest request,
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.PlaceExistingModelAsync(poiId, request, ct);
            return result.IsSuccess
                ? Results.Created($"/api/v1/admin/ar-cards/{result.Value.Id}", result.Value)
                : ToErrorResult(result.Error);
        });

        adminGroup.MapGet("/pois/{poiId:guid}/ar-cards", async (
            Guid poiId,
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.GetArCardsByPoiForAdminAsync(poiId, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        });

        adminGroup.MapPost("/pois/{poiId:guid}/ar-cards", async (
            Guid poiId,
            [FromBody] UpsertArInfoCardRequest request,
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.CreateArCardAsync(poiId, request, ct);
            return result.IsSuccess
                ? Results.Created($"/api/v1/admin/ar-cards/{result.Value.Id}", result.Value)
                : ToErrorResult(result.Error);
        });

        adminGroup.MapPut("/ar-cards/{cardId:guid}", async (
            Guid cardId,
            [FromBody] UpsertArInfoCardRequest request,
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.UpdateArCardAsync(cardId, request, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        });

        adminGroup.MapDelete("/ar-cards/{cardId:guid}", async (
            Guid cardId,
            IArExperienceService arExperienceService,
            CancellationToken ct) =>
        {
            var result = await arExperienceService.DeleteArCardAsync(cardId, ct);
            return result.IsSuccess
                ? Results.NoContent()
                : ToErrorResult(result.Error);
        });

        return app;
    }

    private static IResult ToErrorResult(Error error)
    {
        var statusCode = (int)error.Type;
        return Results.Json(
            new
            {
                code = error.Code,
                message = error.Message,
                error = new
                {
                    code = error.Code,
                    message = error.Message
                }
            },
            statusCode: statusCode);
    }
}
