using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using Microsoft.OpenApi.Models;
using SightseeingTravel.Api.Infrastructure;
using SightseeingTravel.Modules.ArExperience;
using SightseeingTravel.Modules.Attractions;
using SightseeingTravel.Modules.CommunityMedia;
using SightseeingTravel.Modules.Identity;
using SightseeingTravel.Modules.Identity.Infrastructure;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Domain;

var builder = WebApplication.CreateBuilder(args);

// 1. Register SharedKernel & Cross-Cutting Infrastructure Services
builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentUser, CurrentUser>();
builder.Services.AddSingleton<IDateTimeProvider, SystemDateTimeProvider>();
builder.Services.AddHttpClient("OsrmRouting", client =>
{
    var baseUrl = builder.Configuration["Routing:BaseUrl"] ?? "http://127.0.0.1:5000/";
    client.BaseAddress = new Uri(baseUrl, UriKind.Absolute);
    client.Timeout = TimeSpan.FromSeconds(20);
});
builder.Services.AddHttpClient("OsrmWalkingRouting", client =>
{
    var baseUrl = builder.Configuration["Routing:WalkingBaseUrl"] ?? "http://127.0.0.1:5001/";
    client.BaseAddress = new Uri(baseUrl, UriKind.Absolute);
    client.Timeout = TimeSpan.FromSeconds(20);
});

builder.Services.Configure<ContaboStorageOptions>(
    builder.Configuration.GetSection(ContaboStorageOptions.SectionName));
builder.Services.AddSingleton<IObjectStorageService, ContaboS3ObjectStorageService>();

// 2. Register the 4 Modular Monolith Business Modules
builder.Services.AddIdentityModule(builder.Configuration);
builder.Services.AddAttractionsModule(builder.Configuration);
builder.Services.AddArExperienceModule(builder.Configuration);
builder.Services.AddCommunityMediaModule(builder.Configuration);

// 3. Configure Self-Hosted JWT Authentication & 3-Tier RBAC Policies
var jwtOptions = builder.Configuration.GetSection(JwtOptions.SectionName).Get<JwtOptions>()
                 ?? new JwtOptions();

if (Encoding.UTF8.GetByteCount(jwtOptions.SigningKey) < 32)
{
    throw new InvalidOperationException(
        "Jwt:SigningKey must be configured with at least 32 bytes before the API can start.");
}

builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.MapInboundClaims = false;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = jwtOptions.Issuer,
            ValidateAudience = true,
            ValidAudience = jwtOptions.Audience,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtOptions.SigningKey)),
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30),
            NameClaimType = "sub",
            RoleClaimType = "role"
        };
    });

builder.Services.AddAuthorization(options =>
{
    options.AddPolicy(AuthPolicies.AuthenticatedUser, policy =>
        policy.RequireAuthenticatedUser()
              .RequireAssertion(ctx =>
                  HasAnyRole(ctx.User, UserRole.Tourist, UserRole.Admin, UserRole.SuperAdmin)));

    options.AddPolicy(AuthPolicies.AdminOrSuperAdmin, policy =>
        policy.RequireAuthenticatedUser()
              .RequireAssertion(ctx =>
                  HasAnyRole(ctx.User, UserRole.Admin, UserRole.SuperAdmin)));

    options.AddPolicy(AuthPolicies.SuperAdminOnly, policy =>
        policy.RequireAuthenticatedUser()
              .RequireAssertion(ctx =>
                  HasAnyRole(ctx.User, UserRole.SuperAdmin)));
});

// 4. Configure CORS & OpenAPI/Swagger Documentation
builder.Services.AddCors(options =>
{
    options.AddPolicy("MobileClients", policy =>
        policy.AllowAnyOrigin()
              .AllowAnyHeader()
              .AllowAnyMethod());
});

builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new OpenApiInfo
    {
        Title = "Dấu Viễn Du API",
        Version = "v1",
        Description = "Modular Monolith Backend API for Cultural Sightseeing, GPS Discovery, AR Camera & Community Photo Sharing."
    });

    var securityScheme = new OpenApiSecurityScheme
    {
        Name = "Authorization",
        Description = "Enter Bearer JWT token: Bearer {token}",
        In = ParameterLocation.Header,
        Type = SecuritySchemeType.Http,
        Scheme = "bearer",
        BearerFormat = "JWT",
        Reference = new OpenApiReference
        {
            Id = JwtBearerDefaults.AuthenticationScheme,
            Type = ReferenceType.SecurityScheme
        }
    };

    options.AddSecurityDefinition(JwtBearerDefaults.AuthenticationScheme, securityScheme);
    options.AddSecurityRequirement(new OpenApiSecurityRequirement
    {
        { securityScheme, Array.Empty<string>() }
    });
});

var app = builder.Build();

app.UseSwagger();
app.UseSwaggerUI(c =>
{
    c.SwaggerEndpoint("/swagger/v1/swagger.json", "Dấu Viễn Du API v1");
    c.RoutePrefix = "swagger";
});

app.UseCors("MobileClients");
app.UseAuthentication();
app.UseAuthorization();

// 5. Map Health Check & Modular Endpoints
app.MapGet("/api/v1/health", () => Results.Ok(new
{
    status = "Healthy",
    service = "SightseeingTravel.Api",
    architecture = "ModularMonolith",
    modules = new[] { "Identity", "Attractions", "ArExperience", "CommunityMedia" },
    timestampUtc = DateTimeOffset.UtcNow
})).AllowAnonymous().WithTags("Health");

app.MapIdentityEndpoints();
app.MapAttractionsEndpoints();
app.MapArExperienceEndpoints();
app.MapCommunityMediaEndpoints();
app.MapNavigationEndpoints();

// 6. Optional Database Migration & Seed on Startup when configured
if (app.Configuration.GetValue("Database:ApplyMigrationsAndSeedOnStartup", false))
{
    using var scope = app.Services.CreateScope();
    await DatabaseInitializerAndSeeder.InitializeAndSeedAsync(scope.ServiceProvider);
}

app.Run();

static bool HasAnyRole(ClaimsPrincipal principal, params UserRole[] allowedRoles)
{
    var roleClaim = principal.FindFirstValue(ClaimTypes.Role)
                    ?? principal.FindFirstValue("role");

    if (!Enum.TryParse<UserRole>(roleClaim, ignoreCase: true, out var userRole))
        return false;

    return allowedRoles.Contains(userRole);
}

public partial class Program;
