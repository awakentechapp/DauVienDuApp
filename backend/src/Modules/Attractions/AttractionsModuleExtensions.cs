using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using SightseeingTravel.Modules.Attractions.Application;
using SightseeingTravel.Modules.Attractions.Infrastructure;
using SightseeingTravel.Modules.Attractions.Presentation;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.Attractions;

public static class AttractionsModuleExtensions
{
    public static IServiceCollection AddAttractionsModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString =
            configuration.GetConnectionString("DefaultConnection") ??
            configuration.GetConnectionString("Database") ??
            throw new InvalidOperationException("Database connection string is not configured for Attractions module.");

        return services.AddAttractionsModule(connectionString);
    }

    public static IServiceCollection AddAttractionsModule(
        this IServiceCollection services,
        string connectionString)
    {
        services.AddDbContext<AttractionsDbContext>(options =>
            options.UseNpgsql(
                connectionString,
                npgsql => npgsql.MigrationsHistoryTable("__EFMigrationsHistory", AttractionsDbContext.SchemaName)));

        services.AddScoped<IAttractionService, AttractionService>();
        services.AddScoped<IAttractionGeofenceQueryService, AttractionGeofenceQueryService>();

        return services;
    }

    public static IEndpointRouteBuilder MapAttractionsEndpoints(this IEndpointRouteBuilder app) =>
        AttractionEndpoints.MapEndpoints(app);
}
