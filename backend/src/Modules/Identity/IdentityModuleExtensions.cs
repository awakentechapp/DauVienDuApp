using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using SightseeingTravel.Modules.Identity.Application;
using SightseeingTravel.Modules.Identity.Infrastructure;
using SightseeingTravel.Modules.Identity.Presentation;

namespace SightseeingTravel.Modules.Identity;

public static class IdentityModuleExtensions
{
    public static IServiceCollection AddIdentityModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        services.Configure<JwtOptions>(configuration.GetSection(JwtOptions.SectionName));

        var connectionString = configuration.GetConnectionString("DefaultConnection")
            ?? configuration.GetConnectionString("Database")
            ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is required for the Identity module.");

        services.AddDbContext<IdentityDbContext>(options =>
        {
            options.UseNpgsql(
                connectionString,
                npgsql => npgsql.MigrationsHistoryTable("__EFMigrationsHistory", IdentityDbContext.SchemaName));
        });

        services.AddSingleton<PasswordHasher>();
        services.AddSingleton<JwtTokenGenerator>();
        services.AddScoped<IIdentityService, IdentityService>();

        return services;
    }

    public static IEndpointRouteBuilder MapIdentityEndpoints(this IEndpointRouteBuilder app)
    {
        return IdentityEndpoints.Map(app);
    }
}
