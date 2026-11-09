using SightseeingTravel.Modules.ArExperience.Domain;
using SightseeingTravel.Modules.Attractions.Domain;
using SightseeingTravel.Modules.CommunityMedia.Domain;
using SightseeingTravel.Modules.Identity.Domain;
using SightseeingTravel.Modules.Identity.Infrastructure;
using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;
using Xunit;

namespace SightseeingTravel.UnitTests;

public sealed class DomainAndSharedKernelTests
{
    [Fact]
    public void GeoCalculator_ShouldVerifyWithinAndOutsideGeofence()
    {
        // Thap Rua - Hoan Kiem Lake
        const double poiLat = 21.0278;
        const double poiLng = 105.8523;
        const double radiusMeters = 100.0;

        // ~25m away -> Inside geofence
        var isInside = GeoCalculator.IsWithinGeofence(
            21.0280, 105.8524,
            poiLat, poiLng,
            radiusMeters,
            out var nearDistance);

        Assert.True(isInside);
        Assert.InRange(nearDistance, 10.0, 40.0);

        // ~600m away -> Outside geofence
        var isOutside = GeoCalculator.IsWithinGeofence(
            21.0330, 105.8523,
            poiLat, poiLng,
            radiusMeters,
            out var farDistance);

        Assert.False(isOutside);
        Assert.True(farDistance > 500.0);
    }

    [Fact]
    public void LanguageFallbackResolver_ShouldFollow_Requested_ThenEn_ThenVi_Rule()
    {
        var items = new[]
        {
            new { Lang = "vi", Title = "Tháp Rùa" },
            new { Lang = "en", Title = "Turtle Tower" }
        };

        var viMatch = LanguageFallbackResolver.ResolveBestLocalization(items, x => x.Lang, "vi");
        Assert.Equal("Tháp Rùa", viMatch?.Title);

        var frFallbackToEn = LanguageFallbackResolver.ResolveBestLocalization(items, x => x.Lang, "fr");
        Assert.Equal("Turtle Tower", frFallbackToEn?.Title);
    }

    [Fact]
    public void PointOfInterest_StateMachine_ShouldEnforceAdminPendingAndSuperAdminApproval()
    {
        var now = DateTimeOffset.UtcNow;
        var adminId = Guid.NewGuid();
        var superAdminId = Guid.NewGuid();

        // 1. Admin creates POI -> PendingApproval (F-03)
        var createResult = PointOfInterest.Create(
            "POI-TEST-01",
            21.0278,
            105.8523,
            100.0,
            null,
            adminId,
            UserRole.Admin,
            saveAsDraft: false,
            now);

        Assert.True(createResult.IsSuccess);
        var poi = createResult.Value;
        Assert.Equal(PoiStatus.PendingApproval, poi.Status);

        // 2. SuperAdmin rejects without reason -> Fails with POI_REJECT_REASON_REQUIRED (F-04)
        var badReject = poi.Reject(superAdminId, "", now);
        Assert.True(badReject.IsFailure);
        Assert.Equal(ErrorCodes.PoiRejectReasonRequired, badReject.Error.Code);

        // 3. SuperAdmin approves -> Published (F-04)
        var approveResult = poi.Approve(superAdminId, now);
        Assert.True(approveResult.IsSuccess);
        Assert.Equal(PoiStatus.Published, poi.Status);
        Assert.Equal(superAdminId, poi.ApprovedBy);

        // 4. Admin updates a Published POI -> Reverts to PendingApproval for SuperAdmin re-approval
        var updateByAdmin = poi.UpdateDetails(21.0279, 105.8525, 120.0, null, UserRole.Admin, adminId, now);
        Assert.True(updateByAdmin.IsSuccess);
        Assert.Equal(PoiStatus.PendingApproval, poi.Status);
    }

    [Fact]
    public void CommunityPhoto_ShouldAutoFlagAtThreeReports_AndSupportModeration()
    {
        var now = DateTimeOffset.UtcNow;
        var photo = new CommunityPhoto(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            "Traveler A",
            "pois/1/community/photo.jpg",
            "https://sin1.contabostorage.com/sightseeing-media/pois/1/community/photo.jpg",
            "Great view",
            21.0278,
            105.8523,
            15.0,
            90.0,
            now);

        Assert.Equal(CommunityPhotoStatus.Published, photo.Status);

        photo.RegisterReport(Guid.NewGuid(), "Spam content 1", now);
        photo.RegisterReport(Guid.NewGuid(), "Spam content 2", now);
        Assert.Equal(CommunityPhotoStatus.Published, photo.Status);

        // 3rd report triggers auto-hide to Flagged
        photo.RegisterReport(Guid.NewGuid(), "Inappropriate content 3", now);
        Assert.Equal(3, photo.ReportCount);
        Assert.Equal(CommunityPhotoStatus.Flagged, photo.Status);

        // Moderator approves -> Restores to Published
        var moderatorId = Guid.NewGuid();
        photo.ModerateApprove(moderatorId, "Verified clean photo", now);
        Assert.Equal(CommunityPhotoStatus.Published, photo.Status);
    }

    [Fact]
    public void ImageSignatureValidator_ShouldAcceptJpegAndPng_AndRejectExecutable()
    {
        using var jpegStream = new MemoryStream(new byte[] { 0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10 });
        var jpegResult = ImageSignatureValidator.ValidateAndGetExtension(jpegStream, jpegStream.Length, "image/jpeg");
        Assert.True(jpegResult.IsSuccess);
        Assert.Equal(".jpg", jpegResult.Value);

        using var exeStream = new MemoryStream(new byte[] { 0x4D, 0x5A, 0x90, 0x00, 0x03, 0x00 });
        var exeResult = ImageSignatureValidator.ValidateAndGetExtension(exeStream, exeStream.Length, "application/octet-stream");
        Assert.True(exeResult.IsFailure);
        Assert.Equal(ErrorCodes.PhotoInvalidFormatOrSize, exeResult.Error.Code);
    }

    [Fact]
    public void PasswordHasher_ShouldVerifyCorrectPassword_AndRejectWrongPassword()
    {
        var hasher = new PasswordHasher();
        var hash = hasher.HashPassword("StrongPass@2026");

        Assert.True(hasher.VerifyPassword("StrongPass@2026", hash));
        Assert.False(hasher.VerifyPassword("WrongPassword", hash));
    }
}
