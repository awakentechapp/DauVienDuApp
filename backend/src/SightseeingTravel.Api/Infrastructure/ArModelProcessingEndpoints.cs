using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.ArExperience.Infrastructure;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Api.Infrastructure;

public static class ArModelProcessingEndpoints
{
    private const long MaxRequestBytes = 130L * 1024 * 1024;

    public static IEndpointRouteBuilder MapArModelProcessingEndpoints(this IEndpointRouteBuilder app)
    {
        var adminGroup = app.MapGroup("/api/v1/admin/ar-cards")
            .RequireAuthorization(AuthPolicies.AdminOrSuperAdmin)
            .WithTags("Admin - AR 3D Models");

        adminGroup.MapPost("/{cardId:guid}/3d-model", async (
                Guid cardId,
                HttpRequest request,
                IArModelTaskSubmissionService taskSubmissionService,
                CancellationToken cancellationToken) =>
            {
                var form = await request.ReadFormAsync(cancellationToken);
                var result = await taskSubmissionService.SubmitAsync(
                    cardId,
                    form.Files.ToArray(),
                    cancellationToken);

                var body = new
                {
                    cardId,
                    status = result.Status,
                    message = result.Message
                };

                return result.Success
                    ? Results.Json(body, statusCode: StatusCodes.Status202Accepted)
                    : Results.Json(new { code = "AR_MODEL_BUILD_FAILED", message = result.Message },
                        statusCode: result.StatusCode);
            })
            .WithMetadata(new RequestSizeLimitAttribute(MaxRequestBytes))
            .DisableAntiforgery();

        adminGroup.MapGet("/{cardId:guid}/3d-model-status", async (
            Guid cardId,
            ArExperienceDbContext dbContext,
            CancellationToken cancellationToken) =>
        {
            var card = await dbContext.ArInfoCards
                .AsNoTracking()
                .Where(x => x.Id == cardId)
                .Select(x => new { x.Id, x.ModelStatus, x.ModelError })
                .FirstOrDefaultAsync(cancellationToken);

            return card is null
                ? Results.NotFound(new { code = "AR_CARD_NOT_FOUND", message = "Không tìm thấy thẻ AR." })
                : Results.Ok(new ArModelBuildStatusResponse(card.Id, card.ModelStatus, card.ModelError));
        });

        return app;
    }
}
