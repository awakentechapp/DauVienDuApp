# ============================================================================
# SIGHTSEEING TRAVEL - ASP.NET CORE 8.0 MODULAR MONOLITH DOCKERFILE
# ============================================================================
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

COPY ["SightseeingTravel.sln", "./"]
COPY ["src/SightseeingTravel.SharedKernel/SightseeingTravel.SharedKernel.csproj", "src/SightseeingTravel.SharedKernel/"]
COPY ["src/Modules/Identity/SightseeingTravel.Modules.Identity.csproj", "src/Modules/Identity/"]
COPY ["src/Modules/Attractions/SightseeingTravel.Modules.Attractions.csproj", "src/Modules/Attractions/"]
COPY ["src/Modules/ArExperience/SightseeingTravel.Modules.ArExperience.csproj", "src/Modules/ArExperience/"]
COPY ["src/Modules/CommunityMedia/SightseeingTravel.Modules.CommunityMedia.csproj", "src/Modules/CommunityMedia/"]
COPY ["src/SightseeingTravel.Api/SightseeingTravel.Api.csproj", "src/SightseeingTravel.Api/"]
COPY ["tests/SightseeingTravel.UnitTests/SightseeingTravel.UnitTests.csproj", "tests/SightseeingTravel.UnitTests/"]
COPY ["tests/SightseeingTravel.IntegrationTests/SightseeingTravel.IntegrationTests.csproj", "tests/SightseeingTravel.IntegrationTests/"]

RUN dotnet restore "src/SightseeingTravel.Api/SightseeingTravel.Api.csproj"

COPY . .
WORKDIR "/src/src/SightseeingTravel.Api"
RUN dotnet publish "SightseeingTravel.Api.csproj" -c Release -o /app/publish /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS final
WORKDIR /app
EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:8080
COPY --from=build /app/publish .
ENTRYPOINT ["dotnet", "SightseeingTravel.Api.dll"]
