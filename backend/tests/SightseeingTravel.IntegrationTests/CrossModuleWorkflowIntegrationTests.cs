using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.ArExperience.Application;
using SightseeingTravel.Modules.ArExperience.Infrastructure;
using SightseeingTravel.Modules.Attractions.Application;
using SightseeingTravel.Modules.Attractions.Infrastructure;
using SightseeingTravel.Modules.CommunityMedia.Application;
using SightseeingTravel.Modules.CommunityMedia.Infrastructure;
using SightseeingTravel.SharedKernel.Abstractions;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;
using Xunit;

namespace SightseeingTravel.IntegrationTests;

public sealed class CrossModuleWorkflowIntegrationTests
{
    private sealed class FakeCurrentUser : ICurrentUser
    {
        public Guid UserId { get; set; } = Guid.NewGuid();
        public string Email { get; set; } = "user@sightseeing.vn";
        public string DisplayName { get; set; } = "Test User";
        public UserRole Role { get; set; } = UserRole.Tourist;
        public string PreferredLanguage { get; set; } = "vi";
        public bool IsAuthenticated { get; set; } = true;
    }

    private sealed class FakeDateTimeProvider : IDateTimeProvider
    {
        public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
    }

    private sealed class FakeContaboObjectStorageService : IObjectStorageService
    {
        public List<string> UploadedKeys { get; } = new();

        public Task<StorageUploadResult> UploadPhotoAsync(
            Stream fileStream,
            string contentType,
            string objectKey,
            CancellationToken cancellationToken = default)
        {
            UploadedKeys.Add(objectKey);
            return Task.FromResult(new StorageUploadResult(
                objectKey,
                $"https://sin1.contabostorage.com/sightseeing-media/{objectKey}",
                fileStream.CanSeek ? fileStream.Length : 1024,
                contentType));
        }

        public string CreateReadUrl(string objectKey) =>
            $"https://sin1.contabostorage.com/sightseeing-media/{objectKey}";

        public Task DeleteObjectAsync(string objectKey, CancellationToken cancellationToken = default)
        {
            UploadedKeys.Remove(objectKey);
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task EndToEnd_AdminCreatesPoi_SuperAdminApproves_TouristOpensArAndSharesPhoto()
    {
        var dbName = Guid.NewGuid().ToString();
        var currentUser = new FakeCurrentUser
        {
            UserId = Guid.NewGuid(),
            Role = UserRole.Admin,
            DisplayName = "Content Admin",
            PreferredLanguage = "vi"
        };
        var clock = new FakeDateTimeProvider();
        var storage = new FakeContaboObjectStorageService();

        var attractionsOptions = new DbContextOptionsBuilder<AttractionsDbContext>()
            .UseInMemoryDatabase($"attractions_{dbName}")
            .Options;
        var arOptions = new DbContextOptionsBuilder<ArExperienceDbContext>()
            .UseInMemoryDatabase($"ar_{dbName}")
            .Options;
        var mediaOptions = new DbContextOptionsBuilder<CommunityMediaDbContext>()
            .UseInMemoryDatabase($"media_{dbName}")
            .Options;

        await using var attractionsDb = new AttractionsDbContext(attractionsOptions);
        await using var arDb = new ArExperienceDbContext(arOptions);
        await using var mediaDb = new CommunityMediaDbContext(mediaOptions);

        var photoArQuery = new CommunityPhotoArQueryService(mediaDb, storage);
        var geofenceQuery = new AttractionGeofenceQueryService(attractionsDb);

        var attractionService = new AttractionService(
            attractionsDb,
            currentUser,
            clock,
            storage,
            photoArQuery);

        var arService = new ArExperienceService(
            arDb,
            geofenceQuery,
            photoArQuery,
            currentUser,
            clock);

        var mediaService = new CommunityMediaService(
            mediaDb,
            geofenceQuery,
            storage,
            currentUser,
            clock);

        // 1. Admin creates POI -> Status is PendingApproval, invisible to Tourists on Map
        var createPoiResult = await attractionService.CreatePoiAsync(new CreatePoiRequest(
            Code: "POI-ONE-PILLAR",
            Latitude: 21.0358,
            Longitude: 105.8336,
            ActivationRadiusMeters: 100.0,
            SaveAsDraft: false,
            CoverImageUrl: null,
            Localizations: new[]
            {
                new PoiLocalizationInputDto("vi", "Chùa Một Cột", "Ngôi chùa có kiến trúc độc đáo nhất châu Á.", "## Lịch sử Chùa Một Cột\nKhởi dựng năm 1049 thời vua Lý Thái Tông.", "Nhà Lý (1049)"),
                new PoiLocalizationInputDto("en", "One Pillar Pagoda", "Iconic lotus-shaped Buddhist temple in Hanoi.", "## History\nBuilt in 1049 under Emperor Ly Thai Tong.", "Ly Dynasty (1049)")
            }));

        Assert.True(createPoiResult.IsSuccess);
        var poiId = createPoiResult.Value.Id;
        Assert.Equal("PendingApproval", createPoiResult.Value.Status);

        var nearbyBeforeApproval = await attractionService.GetNearbyPoisAsync(21.0358, 105.8336, 5.0, "vi");
        Assert.Empty(nearbyBeforeApproval.Value.Items);

        // 2. SuperAdmin approves POI -> Now Published & visible on Map
        currentUser.UserId = Guid.NewGuid();
        currentUser.Role = UserRole.SuperAdmin;

        var approveResult = await attractionService.ApprovePoiAsync(poiId);
        Assert.True(approveResult.IsSuccess);
        Assert.Equal("Published", approveResult.Value.Status);

        // 3. Admin adds an AR Info Card to the POI
        var arCardResult = await arService.CreateArCardAsync(poiId, new UpsertArInfoCardRequest(
            CardType: "StoryHighlight",
            AzimuthDegrees: 60.0,
            DistanceMeters: 5.0,
            ElevationMeters: 1.5,
            DisplayOrder: 1,
            IsActive: true,
            Localizations: new[]
            {
                new ArCardLocalizationInputDto("vi", "Đài Liên Hoa", "Kiến trúc năm 1049", "Ngôi chùa vươn lên từ mặt hồ Linh Chiểu như đóa sen nở."),
                new ArCardLocalizationInputDto("en", "Lotus Pedestal", "1049 Architecture", "Rising from Linh Chieu pond like a blooming lotus flower.")
            }));
        Assert.True(arCardResult.IsSuccess);

        // 4. Tourist arrives at POI GPS location, uploads a photo to Contabo S3, and opens AR Camera
        currentUser.UserId = Guid.NewGuid();
        currentUser.Role = UserRole.Tourist;
        currentUser.DisplayName = "Marie Dubois";

        using var validJpegStream = new MemoryStream(new byte[] { 0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46 });
        var uploadResult = await mediaService.UploadPhotoAsync(new UploadPhotoCommand(
            PoiId: poiId,
            FileStream: validJpegStream,
            FileLength: validJpegStream.Length,
            ContentType: "image/jpeg",
            CapturedLatitude: 21.0359,
            CapturedLongitude: 105.8337,
            AzimuthDegrees: 120.0,
            Caption: "Beautiful lotus temple!"));

        Assert.True(uploadResult.IsSuccess);
        Assert.Single(storage.UploadedKeys);

        // 5. Tourist opens AR Context within geofence -> receives AR Info Card + Highlighted Community Photo
        var arContextResult = await arService.GetArContextForPoiAsync(poiId, 21.0359, 105.8337, "en");
        Assert.True(arContextResult.IsSuccess);
        Assert.Single(arContextResult.Value.ArInfoCards);
        Assert.Equal("Lotus Pedestal", arContextResult.Value.ArInfoCards[0].Title);
        Assert.Single(arContextResult.Value.HighlightedCommunityPhotos);
        Assert.Equal("Beautiful lotus temple!", arContextResult.Value.HighlightedCommunityPhotos[0].Caption);

        // 6. Tourist tries to open AR Context from 5km away -> Rejected with POI_OUT_OF_GEOFENCE_RANGE (422)
        var farArResult = await arService.GetArContextForPoiAsync(poiId, 21.0800, 105.8337, "en");
        Assert.True(farArResult.IsFailure);
        Assert.Equal(ErrorCodes.PoiOutOfGeofenceRange, farArResult.Error.Code);
    }
}
