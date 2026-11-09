using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using SightseeingTravel.Modules.CommunityMedia.Application;
using SightseeingTravel.Modules.CommunityMedia.Infrastructure;
using SightseeingTravel.Modules.CommunityMedia.Presentation;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.CommunityMedia;

public static class CommunityMediaModuleExtensions
{
    public static IServiceCollection AddCommunityMediaModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString =
            configuration.GetConnectionString("DefaultConnection") ??
            configuration["Database:ConnectionString"] ??
            "Host=localhost;Port=5432;Database=sightseeing_travel;Username=postgres;Password=postgres";

        return services.AddCommunityMediaModule(connectionString);
    }

    public static IServiceCollection AddCommunityMediaModule(
        this IServiceCollection services,
        string connectionString)
    {
        services.AddDbContext<CommunityMediaDbContext>(options =>
        {
            options.UseNpgsql(connectionString, npgsql =>
            {
                npgsql.MigrationsHistoryTable("__EFMigrationsHistory", CommunityMediaDbContext.Schema);
            });
        });

        services.AddScoped<ICommunityMediaService, CommunityMediaService>();
        services.AddScoped<ICommunityPhotoArQueryService, CommunityPhotoArQueryService>();

        return services;
    }

    public static IEndpointRouteBuilder MapCommunityMediaEndpoints(this IEndpointRouteBuilder app)
    {
        return CommunityMediaEndpoints.MapEndpoints(app);
    }
}
