using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using SightseeingTravel.Modules.ArExperience.Application;
using SightseeingTravel.Modules.ArExperience.Infrastructure;
using SightseeingTravel.Modules.ArExperience.Presentation;

namespace SightseeingTravel.Modules.ArExperience;

public static class ArExperienceModuleExtensions
{
    public static IServiceCollection AddArExperienceModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString = configuration.GetConnectionString("DefaultConnection")
                               ?? configuration["Database:ConnectionString"];

        return services.AddArExperienceModule(connectionString);
    }

    public static IServiceCollection AddArExperienceModule(
        this IServiceCollection services,
        string? connectionString)
    {
        services.AddDbContext<ArExperienceDbContext>(options =>
        {
            if (!string.IsNullOrWhiteSpace(connectionString))
            {
                options.UseNpgsql(
                    connectionString,
                    npgsql => npgsql.MigrationsHistoryTable(
                        "__EFMigrationsHistory",
                        ArExperienceDbContext.SchemaName));
            }
        });

        services.AddScoped<IArExperienceService, ArExperienceService>();

        return services;
    }

    public static IEndpointRouteBuilder MapArExperienceEndpoints(this IEndpointRouteBuilder app) =>
        ArExperienceEndpoints.MapArExperienceEndpoints(app);
}
