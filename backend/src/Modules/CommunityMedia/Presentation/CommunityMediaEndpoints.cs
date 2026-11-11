using System.Globalization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Routing;
using SightseeingTravel.Modules.CommunityMedia.Application;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.CommunityMedia.Presentation;

public static class CommunityMediaEndpoints
{
    public static IEndpointRouteBuilder MapEndpoints(IEndpointRouteBuilder app)
    {
        var touristGroup = app.MapGroup("/api/v1")
            .WithTags("CommunityMedia")
            .RequireAuthorization(AuthPolicies.AuthenticatedUser);

        // GET /api/v1/pois/{poiId:guid}/photos?sortBy=popular|latest&page=1&pageSize=20
        touristGroup.MapGet("/pois/{poiId:guid}/photos", async (
            Guid poiId,
            [FromQuery] string? sortBy,
            [FromQuery] int? page,
            [FromQuery] int? pageSize,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.GetPoiPhotosAsync(
                poiId,
                string.IsNullOrWhiteSpace(sortBy) ? "popular" : sortBy,
                page ?? 1,
                pageSize ?? 20,
                ct);

            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result);
        });

        // POST /api/v1/pois/{poiId:guid}/photos
        touristGroup.MapPost("/pois/{poiId:guid}/photos", async (
            Guid poiId,
            HttpRequest request,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            if (!request.HasFormContentType)
            {
                return ToProblemResult(Result.Failure(new Error(
                    ErrorCodes.PhotoInvalidFormatOrSize,
                    "Multipart form-data request with an image file is required.",
                    ErrorType.Validation)));
            }

            var form = await request.ReadFormAsync(ct);
            var file = form.Files.GetFile("file") ?? form.Files.FirstOrDefault();
            if (file is null || file.Length <= 0)
            {
                return ToProblemResult(Result.Failure(new Error(
                    ErrorCodes.PhotoInvalidFormatOrSize,
                    "A non-empty image file is required in form field 'file'.",
                    ErrorType.Validation)));
            }

            if (!TryParseDouble(form["capturedLatitude"], out var capturedLatitude) ||
                !TryParseDouble(form["capturedLongitude"], out var capturedLongitude))
            {
                return ToProblemResult(Result.Failure(new Error(
                    ErrorCodes.PoiOutOfGeofenceRange,
                    "Valid 'capturedLatitude' and 'capturedLongitude' form fields are required.",
                    ErrorType.Validation)));
            }

            double? azimuthDegrees = null;
            var rawAzimuth = form["azimuthDegrees"].FirstOrDefault();
            if (!string.IsNullOrWhiteSpace(rawAzimuth))
            {
                if (!TryParseDouble(rawAzimuth, out var parsedAzimuth))
                {
                    return ToProblemResult(Result.Failure(new Error(
                        "PHOTO_INVALID_AZIMUTH",
                        "Invalid 'azimuthDegrees' format.",
                        ErrorType.Validation)));
                }
                azimuthDegrees = parsedAzimuth;
            }

            var caption = form["caption"].FirstOrDefault();
            double? pitchDegrees = null;
            var rawPitch = form["pitchDegrees"].FirstOrDefault();
            if (!string.IsNullOrWhiteSpace(rawPitch))
            {
                if (!TryParseDouble(rawPitch, out var parsedPitch) || parsedPitch is < -90.0 or > 90.0)
                {
                    return ToProblemResult(Result.Failure(new Error(
                        "PHOTO_INVALID_PITCH",
                        "Pitch degrees must be between -90 and 90.",
                        ErrorType.Validation)));
                }

                pitchDegrees = parsedPitch;
            }

            await using var fileStream = file.OpenReadStream();
            var command = new UploadPhotoCommand(
                PoiId: poiId,
                FileStream: fileStream,
                FileLength: file.Length,
                ContentType: file.ContentType,
                CapturedLatitude: capturedLatitude,
                CapturedLongitude: capturedLongitude,
                AzimuthDegrees: azimuthDegrees,
                PitchDegrees: pitchDegrees,
                Caption: caption);

            var result = await mediaService.UploadPhotoAsync(command, ct);

            return result.IsSuccess
                ? Results.Created($"/api/v1/pois/{poiId}/photos/{result.Value.Id}", result.Value)
                : ToProblemResult(result);
        })
        .DisableAntiforgery();

        // POST /api/v1/photos/{photoId:guid}/like
        touristGroup.MapPost("/photos/{photoId:guid}/like", async (
            Guid photoId,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.ToggleLikeAsync(photoId, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result);
        });

        // POST /api/v1/photos/{photoId:guid}/report
        touristGroup.MapPost("/photos/{photoId:guid}/report", async (
            Guid photoId,
            [FromBody] ReportPhotoRequest request,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.ReportPhotoAsync(photoId, request.Reason, ct);
            return result.IsSuccess
                ? Results.Ok(new { photoId, status = "Reported" })
                : ToProblemResult(result);
        });

        // DELETE /api/v1/photos/{photoId:guid}
        touristGroup.MapDelete("/photos/{photoId:guid}", async (
            Guid photoId,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.DeleteOwnPhotoAsync(photoId, ct);
            return result.IsSuccess
                ? Results.NoContent()
                : ToProblemResult(result);
        });

        // GET /api/v1/users/me/photos
        touristGroup.MapGet("/users/me/photos", async (
            [FromQuery] int? page,
            [FromQuery] int? pageSize,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.GetMyPhotosAsync(page ?? 1, pageSize ?? 20, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result);
        });

        // Admin & SuperAdmin Moderation Endpoints
        var adminGroup = app.MapGroup("/api/v1/admin/moderation/photos")
            .WithTags("CommunityMedia Moderation")
            .RequireAuthorization(AuthPolicies.AdminOrSuperAdmin);

        // GET /api/v1/admin/moderation/photos
        adminGroup.MapGet("", async (
            [FromQuery] int? page,
            [FromQuery] int? pageSize,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.GetModerationQueueAsync(page ?? 1, pageSize ?? 20, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result);
        });

        // POST /api/v1/admin/moderation/photos/{photoId:guid}/decision
        adminGroup.MapPost("/{photoId:guid}/decision", async (
            Guid photoId,
            [FromBody] ModerationDecisionRequest request,
            ICommunityMediaService mediaService,
            CancellationToken ct) =>
        {
            var result = await mediaService.ModeratePhotoAsync(photoId, request, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToProblemResult(result);
        });

        return app;
    }

    private static bool TryParseDouble(string? rawValue, out double result) =>
        double.TryParse(rawValue, NumberStyles.Float | NumberStyles.AllowThousands, CultureInfo.InvariantCulture, out result);

    private static IResult ToProblemResult(Result result)
    {
        var statusCode = result.Error.Type switch
        {
            ErrorType.Validation => StatusCodes.Status400BadRequest,
            ErrorType.Unauthorized => StatusCodes.Status401Unauthorized,
            ErrorType.Forbidden => StatusCodes.Status403Forbidden,
            ErrorType.NotFound => StatusCodes.Status404NotFound,
            ErrorType.Conflict => StatusCodes.Status409Conflict,
            ErrorType.UnprocessableEntity => StatusCodes.Status422UnprocessableEntity,
            _ => StatusCodes.Status500InternalServerError
        };

        return Results.Json(
            new
            {
                errorCode = result.Error.Code,
                message = result.Error.Message,
                status = statusCode
            },
            statusCode: statusCode);
    }
}
