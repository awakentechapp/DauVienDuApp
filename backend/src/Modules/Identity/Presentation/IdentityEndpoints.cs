using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using SightseeingTravel.Modules.Identity.Application;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.Identity.Presentation;

public static class IdentityEndpoints
{
    public static IEndpointRouteBuilder Map(IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1")
            .WithTags("Identity");

        group.MapPost("/auth/register", async (
            RegisterRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.RegisterTouristAsync(request, ct);
            return result.IsSuccess
                ? Results.Created($"/api/v1/users/{result.Value.User.Id}", result.Value)
                : ToErrorResult(result.Error);
        })
        .AllowAnonymous()
        .WithName("RegisterTourist");

        group.MapPost("/auth/login", async (
            LoginRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.LoginAsync(request, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        })
        .AllowAnonymous()
        .WithName("Login");

        group.MapPost("/auth/refresh", async (
            RefreshTokenRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.RefreshTokenAsync(request, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        })
        .AllowAnonymous()
        .WithName("RefreshToken");

        group.MapPost("/auth/logout", async (
            LogoutRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.LogoutAsync(request, ct);
            return result.IsSuccess
                ? Results.NoContent()
                : ToErrorResult(result.Error);
        })
        .RequireAuthorization(AuthPolicies.AuthenticatedUser)
        .WithName("Logout");

        group.MapGet("/users/me", async (
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.GetCurrentUserProfileAsync(ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        })
        .RequireAuthorization(AuthPolicies.AuthenticatedUser)
        .WithName("GetCurrentUserProfile");

        group.MapPut("/users/me", async (
            UpdateProfileRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.UpdateProfileAsync(request, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        })
        .RequireAuthorization(AuthPolicies.AuthenticatedUser)
        .WithName("UpdateCurrentUserProfile");

        group.MapGet("/admin/users", async (
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.GetAllUsersAsync(ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        })
        .RequireAuthorization(AuthPolicies.SuperAdminOnly)
        .WithName("GetAllUsers");

        group.MapPost("/admin/users", async (
            CreateAdminUserRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.CreateStaffUserAsync(request, ct);
            return result.IsSuccess
                ? Results.Created($"/api/v1/admin/users/{result.Value.Id}", result.Value)
                : ToErrorResult(result.Error);
        })
        .RequireAuthorization(AuthPolicies.SuperAdminOnly)
        .WithName("CreateStaffUser");

        group.MapPatch("/admin/users/{id:guid}/status", async (
            Guid id,
            UpdateUserStatusRequest request,
            IIdentityService identityService,
            CancellationToken ct) =>
        {
            var result = await identityService.SetUserActiveStatusAsync(id, request, ct);
            return result.IsSuccess
                ? Results.Ok(result.Value)
                : ToErrorResult(result.Error);
        })
        .RequireAuthorization(AuthPolicies.SuperAdminOnly)
        .WithName("UpdateUserStatus");

        return app;
    }

    private static IResult ToErrorResult(Error error)
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
