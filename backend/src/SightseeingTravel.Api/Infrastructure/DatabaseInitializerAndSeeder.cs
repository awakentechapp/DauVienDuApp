using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using SightseeingTravel.Modules.ArExperience.Domain;
using SightseeingTravel.Modules.ArExperience.Infrastructure;
using SightseeingTravel.Modules.Attractions.Domain;
using SightseeingTravel.Modules.Attractions.Infrastructure;
using SightseeingTravel.Modules.CommunityMedia.Domain;
using SightseeingTravel.Modules.CommunityMedia.Infrastructure;
using SightseeingTravel.Modules.Identity.Domain;
using SightseeingTravel.Modules.Identity.Infrastructure;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Api.Infrastructure;

/// <summary>
/// Initializes the modular PostgreSQL schemas, provisions the first SuperAdmin from deployment configuration,
/// and seeds sample cultural content only outside Production.
/// </summary>
public static class DatabaseInitializerAndSeeder
{
    public static async Task InitializeAndSeedAsync(
        IServiceProvider services,
        CancellationToken cancellationToken = default)
    {
        var identityDb = services.GetRequiredService<IdentityDbContext>();
        var attractionsDb = services.GetRequiredService<AttractionsDbContext>();
        var arDb = services.GetRequiredService<ArExperienceDbContext>();
        var mediaDb = services.GetRequiredService<CommunityMediaDbContext>();
        var passwordHasher = services.GetRequiredService<PasswordHasher>();
        var configuration = services.GetRequiredService<IConfiguration>();
        var environment = services.GetRequiredService<IHostEnvironment>();

        await identityDb.Database.EnsureCreatedAsync(cancellationToken);
        await attractionsDb.Database.EnsureCreatedAsync(cancellationToken);
        await arDb.Database.EnsureCreatedAsync(cancellationToken);
        await mediaDb.Database.EnsureCreatedAsync(cancellationToken);

        var migrationResource = typeof(DatabaseInitializerAndSeeder).Assembly
            .GetManifestResourceStream("SightseeingTravel.Api.Migrations.0002_PoiCategoryPhotoPoseAndArCascade.sql")
            ?? throw new InvalidOperationException("POI category/photo pose migration resource is missing.");
        await using (migrationResource)
        using (var reader = new StreamReader(migrationResource))
        {
            var migrationSql = await reader.ReadToEndAsync(cancellationToken);
            await attractionsDb.Database.ExecuteSqlRawAsync(migrationSql, cancellationToken);
        }

        var arModelMigration = typeof(DatabaseInitializerAndSeeder).Assembly
            .GetManifestResourceStream("SightseeingTravel.Api.Migrations.0003_ArCard3dModel.sql")
            ?? throw new InvalidOperationException("AR 3D model migration resource is missing.");
        await using (arModelMigration)
        using (var reader = new StreamReader(arModelMigration))
        {
            var migrationSql = await reader.ReadToEndAsync(cancellationToken);
            await arDb.Database.ExecuteSqlRawAsync(migrationSql, cancellationToken);
        }

        var arPlacementMigration = typeof(DatabaseInitializerAndSeeder).Assembly
            .GetManifestResourceStream("SightseeingTravel.Api.Migrations.0004_ArCardModelPlacement.sql")
            ?? throw new InvalidOperationException("AR model placement migration resource is missing.");
        await using (arPlacementMigration)
        using (var reader = new StreamReader(arPlacementMigration))
        {
            var migrationSql = await reader.ReadToEndAsync(cancellationToken);
            await arDb.Database.ExecuteSqlRawAsync(migrationSql, cancellationToken);
        }

        var arModelCatalogMigration = typeof(DatabaseInitializerAndSeeder).Assembly
            .GetManifestResourceStream("SightseeingTravel.Api.Migrations.0005_ArModelCatalogMetadata.sql")
            ?? throw new InvalidOperationException("AR model catalog metadata migration resource is missing.");
        await using (arModelCatalogMigration)
        using (var reader = new StreamReader(arModelCatalogMigration))
        {
            var migrationSql = await reader.ReadToEndAsync(cancellationToken);
            await arDb.Database.ExecuteSqlRawAsync(migrationSql, cancellationToken);
        }

        var now = DateTimeOffset.UtcNow;

        var superAdmin = await identityDb.Users
            .FirstOrDefaultAsync(u => u.Role == UserRole.SuperAdmin, cancellationToken);

        if (superAdmin is null)
        {
            var bootstrapEmail = configuration["BootstrapAdmin:Email"]?.Trim();
            var bootstrapPassword = configuration["BootstrapAdmin:Password"];

            if (string.IsNullOrWhiteSpace(bootstrapEmail)
                || string.IsNullOrWhiteSpace(bootstrapPassword)
                || bootstrapPassword.Length < 12)
            {
                throw new InvalidOperationException(
                    "BootstrapAdmin:Email and a BootstrapAdmin:Password of at least 12 characters are required when no SuperAdmin exists.");
            }

            bootstrapEmail = User.NormalizeEmail(bootstrapEmail);

            if (await identityDb.Users.AnyAsync(u => u.Email == bootstrapEmail, cancellationToken))
            {
                throw new InvalidOperationException(
                    "The bootstrap administrator email already belongs to a non-SuperAdmin account.");
            }

            superAdmin = User.CreateStaff(
                bootstrapEmail,
                passwordHasher.HashPassword(bootstrapPassword),
                "Quản trị viên hệ thống",
                UserRole.SuperAdmin,
                SupportedLanguages.Vietnamese);

            identityDb.Users.Add(superAdmin);
            await identityDb.SaveChangesAsync(cancellationToken);
        }

        var admin = await identityDb.Users
            .FirstOrDefaultAsync(u => u.Role == UserRole.Admin, cancellationToken) ?? superAdmin;
        var tourist = await identityDb.Users
            .FirstOrDefaultAsync(u => u.Role == UserRole.Tourist, cancellationToken) ?? superAdmin;

        if (environment.IsProduction())
        {
            return;
        }

        // 2. Seed Cultural POIs (Published & PendingApproval)
        if (!await attractionsDb.PointsOfInterest.AnyAsync(cancellationToken))
        {
            var thapRuaResult = PointOfInterest.Create(
                code: "POI-THAP-RUA",
                category: PoiCategories.CulturalHeritage,
                latitude: 21.0278,
                longitude: 105.8523,
                activationRadiusMeters: 120.0,
                coverImageUrl: "https://sin1.contabostorage.com/sightseeing-media/pois/thap-rua/official/cover.jpg",
                createdBy: superAdmin.Id,
                creatorRole: UserRole.SuperAdmin,
                saveAsDraft: false,
                now: now);

            var pendingByAdminResult = PointOfInterest.Create(
                code: "POI-HOANG-THANH",
                category: PoiCategories.HistoricalSite,
                latitude: 21.0341,
                longitude: 105.8401,
                activationRadiusMeters: 150.0,
                coverImageUrl: "https://sin1.contabostorage.com/sightseeing-media/pois/hoang-thanh/official/cover.jpg",
                createdBy: admin.Id,
                creatorRole: admin.Role,
                saveAsDraft: admin.Role != UserRole.Admin,
                now: now);

            if (thapRuaResult.IsSuccess && pendingByAdminResult.IsSuccess)
            {
                var thapRua = thapRuaResult.Value;
                thapRua.Localizations.Add(new PoiLocalization(
                    Guid.NewGuid(),
                    thapRua.Id,
                    "vi",
                    "Tháp Rùa - Hồ Hoàn Kiếm",
                    "Biểu tượng văn hóa ngàn năm gắn liền với truyền thuyết vua Lê Lợi trả gươm báu Thuận Thiên.",
                    "## Truyền thuyết Hồ Hoàn Kiếm\nVào thế kỷ XV, sau khi đánh đuổi quân Minh xâm lược, vua Lê Thái Tổ dạo thuyền trên hồ Lục Thủy. Thần Kim Quy nổi lên xin nhận lại thanh gươm Thuận Thiên. Từ đó hồ mang tên **Hồ Hoàn Kiếm**.",
                    "Thế kỷ XV (Nhà Hậu Lê) - Thế kỷ XIX"));

                thapRua.Localizations.Add(new PoiLocalization(
                    Guid.NewGuid(),
                    thapRua.Id,
                    "en",
                    "Turtle Tower - Hoan Kiem Lake",
                    "Sacred cultural symbol of Hanoi linked to Emperor Le Loi's legend of returning the Heaven's Will Sword.",
                    "## The Legend of the Restored Sword\nIn the 15th century, after defeating the Ming Dynasty, Emperor Le Loi boated on Luc Thuy Lake where the Golden Turtle God surfaced to reclaim the sacred sword **Thuan Thien**.",
                    "15th Century (Le Dynasty) - 19th Century"));

                var pendingPoi = pendingByAdminResult.Value;
                pendingPoi.Localizations.Add(new PoiLocalization(
                    Guid.NewGuid(),
                    pendingPoi.Id,
                    "vi",
                    "Hoàng Thành Thăng Long",
                    "Di sản văn hóa thế giới UNESCO, trung tâm quyền lực suốt 13 thế kỷ.",
                    "## Hoàng Thành Thăng Long\nQuần thể di tích gắn liền với lịch sử kinh thành Thăng Long từ thời tiền Thăng Long qua thời Đinh - Tiền Lê, Lý, Trần, Lê.",
                    "Thế kỷ XI - Thế kỷ XIX"));

                pendingPoi.Localizations.Add(new PoiLocalization(
                    Guid.NewGuid(),
                    pendingPoi.Id,
                    "en",
                    "Imperial Citadel of Thang Long",
                    "UNESCO World Heritage site and political center of Vietnam for 13 consecutive centuries.",
                    "## Imperial Citadel of Thang Long\nBuilt in the 11th century by the Ly Viet Dynasty, marking the independence of Dai Viet.",
                    "11th Century - 19th Century"));

                attractionsDb.PointsOfInterest.AddRange(thapRua, pendingPoi);
                await attractionsDb.SaveChangesAsync(cancellationToken);

                // 3. Seed AR Info Cards for Published POI
                var card1Result = ArInfoCard.Create(
                    poiId: thapRua.Id,
                    cardType: ArCardType.StoryHighlight,
                    azimuthDegrees: 45.0,
                    distanceMeters: 4.5,
                    elevationMeters: 1.2,
                    displayOrder: 1,
                    isActive: true,
                    nowUtc: now);

                var card2Result = ArInfoCard.Create(
                    poiId: thapRua.Id,
                    cardType: ArCardType.ArchitectureNote,
                    azimuthDegrees: 135.0,
                    distanceMeters: 6.0,
                    elevationMeters: 1.8,
                    displayOrder: 2,
                    isActive: true,
                    nowUtc: now);

                if (card1Result.IsSuccess && card2Result.IsSuccess)
                {
                    var card1 = card1Result.Value;
                    card1.ReplaceLocalizations(new[]
                    {
                        ("vi", "Truyền thuyết Rùa Vàng trả kiếm", (string?)"Năm 1428 - Vua Lê Thái Tổ", "Tại vị trí mặt nước phía trước bạn là nơi truyền thuyết kể rằng Thần Kim Quy đã nổi lên đón nhận lại thanh kiếm Thuận Thiên."),
                        ("en", "Legend of the Golden Turtle", (string?)"1428 AD - Emperor Le Loi", "Right across the water in front of you is where the Golden Turtle God surfaced to reclaim the sacred Heaven's Will Sword.")
                    });

                    var card2 = card2Result.Value;
                    card2.ReplaceLocalizations(new[]
                    {
                        ("vi", "Kiến trúc giao thoa Á - Âu", (string?)"Xây dựng năm 1886", "Tháp Rùa gồm 3 tầng với vòm cuốn kiểu Gothic ở tầng dưới nhưng mái cong đắp rồng theo kiến trúc cổ truyền Việt Nam."),
                        ("en", "East-Meets-West Architecture", (string?)"Built in 1886", "Turtle Tower blends three-story European Gothic arches at the base with traditional Vietnamese curved roof ridges.")
                    });

                    arDb.ArInfoCards.AddRange(card1, card2);
                    await arDb.SaveChangesAsync(cancellationToken);
                }

                // 4. Seed Sample Community Photo for Published POI
                var samplePhoto = new CommunityPhoto(
                    id: Guid.NewGuid(),
                    poiId: thapRua.Id,
                    userId: tourist.Id,
                    authorDisplayName: tourist.DisplayName,
                    objectKey: $"pois/{thapRua.Id}/community/2026/11/sample-sunrise.jpg",
                    imageUrl: "https://sin1.contabostorage.com/sightseeing-media/pois/thap-rua/community/sample-sunrise.jpg",
                    caption: "Morning mist over Turtle Tower captured right from the lakeside!",
                    capturedLatitude: 21.0280,
                    capturedLongitude: 105.8524,
                    distanceFromPoiMeters: 24.5,
                    azimuthDegrees: 95.0,
                    pitchDegrees: 0.0,
                    createdAt: now);

                samplePhoto.IncrementLike();
                samplePhoto.IncrementLike();

                mediaDb.CommunityPhotos.Add(samplePhoto);
                await mediaDb.SaveChangesAsync(cancellationToken);
            }
        }
    }
}
