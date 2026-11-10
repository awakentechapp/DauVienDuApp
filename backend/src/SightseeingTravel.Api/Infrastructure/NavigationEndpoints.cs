using System.Globalization;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Api.Infrastructure;

public static class NavigationEndpoints
{
    private const string RoutingClientName = "OsrmRouting";

    public static IEndpointRouteBuilder MapNavigationEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/navigation")
            .WithTags("Navigation")
            .RequireAuthorization(AuthPolicies.AuthenticatedUser);

        group.MapPost("/route", async (
            CalculateRouteRequest request,
            IHttpClientFactory httpClientFactory,
            CancellationToken cancellationToken) =>
        {
            if (!IsValidCoordinate(request.OriginLatitude, request.OriginLongitude) ||
                !IsValidCoordinate(request.DestinationLatitude, request.DestinationLongitude))
            {
                return Results.BadRequest(new { message = "Origin and destination coordinates are invalid." });
            }

            var coordinates = string.Join(";", new[]
            {
                FormatCoordinate(request.OriginLongitude, request.OriginLatitude),
                FormatCoordinate(request.DestinationLongitude, request.DestinationLatitude)
            });
            var routeUri = $"route/v1/driving/{coordinates}?overview=full&geometries=geojson";

            try
            {
                var client = httpClientFactory.CreateClient(RoutingClientName);
                using var response = await client.GetAsync(routeUri, cancellationToken);
                if (!response.IsSuccessStatusCode)
                {
                    return Results.Problem(
                        title: "Routing engine is unavailable.",
                        statusCode: StatusCodes.Status503ServiceUnavailable);
                }

                await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
                using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
                var root = document.RootElement;
                var code = root.TryGetProperty("code", out var codeElement)
                    ? codeElement.GetString()
                    : null;

                if (!string.Equals(code, "Ok", StringComparison.Ordinal))
                {
                    return Results.UnprocessableEntity(new { message = "No drivable route was found." });
                }

                if (!root.TryGetProperty("routes", out var routes) ||
                    routes.ValueKind != JsonValueKind.Array || routes.GetArrayLength() == 0)
                {
                    return Results.UnprocessableEntity(new { message = "No drivable route was found." });
                }

                var route = routes[0];
                var geometry = route.GetProperty("geometry");
                var rawCoordinates = geometry.GetProperty("coordinates");
                var path = rawCoordinates.EnumerateArray()
                    .Where(point => point.ValueKind == JsonValueKind.Array && point.GetArrayLength() >= 2)
                    .Select(point => new RouteCoordinate(
                        Latitude: point[1].GetDouble(),
                        Longitude: point[0].GetDouble()))
                    .Where(point => IsValidCoordinate(point.Latitude, point.Longitude))
                    .ToArray();

                if (path.Length < 2)
                {
                    return Results.Problem(
                        title: "Routing engine returned an invalid route.",
                        statusCode: StatusCodes.Status502BadGateway);
                }

                return Results.Ok(new CalculateRouteResponse(
                    DistanceMeters: route.GetProperty("distance").GetDouble(),
                    DurationSeconds: route.GetProperty("duration").GetDouble(),
                    Coordinates: path));
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (OperationCanceledException)
            {
                return Results.Problem(
                    title: "Routing engine timed out.",
                    statusCode: StatusCodes.Status503ServiceUnavailable);
            }
            catch (HttpRequestException)
            {
                return Results.Problem(
                    title: "Routing engine is unavailable.",
                    statusCode: StatusCodes.Status503ServiceUnavailable);
            }
            catch (JsonException)
            {
                return Results.Problem(
                    title: "Routing engine returned an invalid response.",
                    statusCode: StatusCodes.Status502BadGateway);
            }
            catch (InvalidOperationException)
            {
                return Results.Problem(
                    title: "Routing engine returned an invalid response.",
                    statusCode: StatusCodes.Status502BadGateway);
            }
        })
        .WithName("CalculateNavigationRoute");

        return app;
    }

    private static bool IsValidCoordinate(double latitude, double longitude) =>
        double.IsFinite(latitude) && double.IsFinite(longitude) &&
        latitude is >= -90 and <= 90 && longitude is >= -180 and <= 180;

    private static string FormatCoordinate(double longitude, double latitude) =>
        string.Concat(
            longitude.ToString("R", CultureInfo.InvariantCulture),
            ",",
            latitude.ToString("R", CultureInfo.InvariantCulture));
}

public sealed record CalculateRouteRequest(
    double OriginLatitude,
    double OriginLongitude,
    double DestinationLatitude,
    double DestinationLongitude);

public sealed record RouteCoordinate(double Latitude, double Longitude);

public sealed record CalculateRouteResponse(
    double DistanceMeters,
    double DurationSeconds,
    IReadOnlyList<RouteCoordinate> Coordinates);
