using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Routing;
using SightseeingTravel.Modules.Attractions.Application;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Attractions.Presentation;

public static class AttractionEndpoints
{
    public static IEndpointRouteBuilder MapEndpoints(IEndpointRouteBuilder app)
    {
        var touristGroup = app.MapGroup("/api/v1/pois")
            .WithTags("Attractions (Tourist)")
            .RequireAuthorization(AuthPolicies.AuthenticatedUser);

        touristGroup.MapGet("/nearby", async (
            [FromQuery] double lat,
            [FromQuery] double lng,
            [FromQuery] double? radiusKm,
            [FromQuery] string? lang,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.GetNearbyPoisAsync(
                lat,
                lng,
                radiusKm ?? 10.0,
                lang,
                cancellationToken);

            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        });

        touristGroup.MapGet("/{poiId:guid}", async (
            Guid poiId,
            [FromQuery] double? lat,
            [FromQuery] double? lng,
            [FromQuery] string? lang,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.GetPoiDetailAsync(
                poiId,
                lat,
                lng,
                lang,
                cancellationToken);

            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        });

        var adminGroup = app.MapGroup("/api/v1/admin/pois")
            .WithTags("Attractions (Admin & Approval)")
            .RequireAuthorization(AuthPolicies.AdminOrSuperAdmin);

        adminGroup.MapGet("/", async (
            [FromQuery] string? status,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.GetAdminPoisAsync(status, cancellationToken);
            return result.IsSuccess
                ? Results.Ok(new { items = result.Value })
                : ToProblemResult(result.Error);
        });

        adminGroup.MapPost("/", async (
            [FromBody] CreatePoiRequest request,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.CreatePoiAsync(request, cancellationToken);
            return result.IsSuccess
                ? Results.Created($"/api/v1/pois/{result.Value.Id}", result.Value)
                : ToProblemResult(result.Error);
        });

        adminGroup.MapPut("/{poiId:guid}", async (
            Guid poiId,
            [FromBody] UpdatePoiRequest request,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.UpdatePoiAsync(poiId, request, cancellationToken);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        });

        adminGroup.MapPost("/{poiId:guid}/cover", async (
            Guid poiId,
            HttpRequest httpRequest,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            if (!httpRequest.HasFormContentType)
            {
                return ToProblemResult(new Error(
                    ErrorCodes.PhotoInvalidFormatOrSize,
                    "Multipart form-data request with an image file is required.",
                    ErrorType.Validation));
            }

            var form = await httpRequest.ReadFormAsync(cancellationToken);
            var file = form.Files.GetFile("file") ?? form.Files.FirstOrDefault();
            if (file is null || file.Length <= 0)
            {
                return ToProblemResult(new Error(
                    ErrorCodes.PhotoInvalidFormatOrSize,
                    "Image file is required and cannot be empty.",
                    ErrorType.Validation));
            }

            await using var memoryStream = new MemoryStream();
            await file.CopyToAsync(memoryStream, cancellationToken);
            memoryStream.Position = 0;

            var result = await attractionService.UploadCoverImageAsync(
                poiId,
                memoryStream,
                memoryStream.Length,
                file.ContentType,
                cancellationToken);

            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        }).DisableAntiforgery();

        adminGroup.MapPost("/{poiId:guid}/submit", async (
            Guid poiId,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.SubmitPoiForApprovalAsync(poiId, cancellationToken);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        });

        var superAdminGroup = app.MapGroup("/api/v1/admin/pois")
            .WithTags("Attractions (SuperAdmin Approval)")
            .RequireAuthorization(AuthPolicies.SuperAdminOnly);

        superAdminGroup.MapPost("/{poiId:guid}/approve", async (
            Guid poiId,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.ApprovePoiAsync(poiId, cancellationToken);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        });

        superAdminGroup.MapPost("/{poiId:guid}/reject", async (
            Guid poiId,
            [FromBody] RejectPoiRequest request,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.RejectPoiAsync(poiId, request, cancellationToken);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result.Error);
        });

        superAdminGroup.MapDelete("/{poiId:guid}", async (
            Guid poiId,
            IAttractionService attractionService,
            CancellationToken cancellationToken) =>
        {
            var result = await attractionService.DeletePoiAsync(poiId, cancellationToken);
            return result.IsSuccess
                ? Results.NoContent()
                : ToProblemResult(result.Error);
        });

        return app;
    }

    private static IResult ToProblemResult(Error error)
    {
        var statusCode = (int)error.Type;
        if (statusCode < 400 || statusCode > 599)
        {
            statusCode = StatusCodes.Status400BadRequest;
        }

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
