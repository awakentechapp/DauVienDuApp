using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.CommunityMedia.Domain;
using SightseeingTravel.Persistence;

namespace SightseeingTravel.Modules.CommunityMedia.Infrastructure;

public sealed class CommunityMediaDbContext : DbContext
{
    public const string Schema = "media";

    public CommunityMediaDbContext(DbContextOptions<CommunityMediaDbContext> options)
        : base(options)
    {
    }

    public DbSet<CommunityPhoto> CommunityPhotos => Set<CommunityPhoto>();
    public DbSet<PhotoLike> PhotoLikes => Set<PhotoLike>();
    public DbSet<PhotoReport> PhotoReports => Set<PhotoReport>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(Schema);

        modelBuilder.Entity<CommunityPhoto>(builder =>
        {
            builder.ToTable("community_photos");
            builder.HasKey(x => x.Id);
            builder.Property(x => x.Id).ValueGeneratedNever();

            builder.Property(x => x.PoiId).IsRequired();
            builder.Property(x => x.UserId).IsRequired();
            builder.Property(x => x.AuthorDisplayName).HasMaxLength(150).IsRequired();
            builder.Property(x => x.ObjectKey).HasMaxLength(512).IsRequired();
            builder.Property(x => x.ImageUrl).HasMaxLength(1024).IsRequired();
            builder.Property(x => x.Caption).HasMaxLength(CommunityPhoto.MaxCaptionLength);
            builder.Property(x => x.CapturedLatitude).IsRequired();
            builder.Property(x => x.CapturedLongitude).IsRequired();
            builder.Property(x => x.DistanceFromPoiMeters).IsRequired();
            builder.Property(x => x.AzimuthDegrees);
            builder.Property(x => x.LikeCount).IsRequired();
            builder.Property(x => x.ReportCount).IsRequired();
            builder.Property(x => x.Status).HasConversion<string>().HasMaxLength(32).IsRequired();
            builder.Property(x => x.ModeratedBy);
            builder.Property(x => x.ModeratedAt);
            builder.Property(x => x.ModerationNote).HasMaxLength(500);
            builder.Property(x => x.CreatedAt).IsRequired();
            builder.Property(x => x.UpdatedAt).IsRequired();

            builder.HasIndex(x => new { x.PoiId, x.Status, x.LikeCount, x.CreatedAt })
                .HasDatabaseName("IX_community_photos_poi_feed");

            builder.HasIndex(x => new { x.UserId, x.CreatedAt })
                .HasDatabaseName("IX_community_photos_user_created");

            builder.HasMany(x => x.Likes)
                .WithOne(x => x.Photo)
                .HasForeignKey(x => x.PhotoId)
                .OnDelete(DeleteBehavior.Cascade);

            builder.HasMany(x => x.Reports)
                .WithOne(x => x.Photo)
                .HasForeignKey(x => x.PhotoId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<PhotoLike>(builder =>
        {
            builder.ToTable("photo_likes");
            builder.HasKey(x => x.Id);
            builder.Property(x => x.Id).ValueGeneratedNever();

            builder.Property(x => x.PhotoId).IsRequired();
            builder.Property(x => x.UserId).IsRequired();
            builder.Property(x => x.CreatedAt).IsRequired();

            builder.HasIndex(x => new { x.PhotoId, x.UserId })
                .IsUnique()
                .HasDatabaseName("UX_photo_likes_photo_user");
        });

        modelBuilder.Entity<PhotoReport>(builder =>
        {
            builder.ToTable("photo_reports");
            builder.HasKey(x => x.Id);
            builder.Property(x => x.Id).ValueGeneratedNever();

            builder.Property(x => x.PhotoId).IsRequired();
            builder.Property(x => x.ReporterUserId).IsRequired();
            builder.Property(x => x.Reason).HasMaxLength(500).IsRequired();
            builder.Property(x => x.Status).HasConversion<string>().HasMaxLength(32).IsRequired();
            builder.Property(x => x.ResolvedBy);
            builder.Property(x => x.ResolvedAt);
            builder.Property(x => x.CreatedAt).IsRequired();

            builder.HasIndex(x => new { x.PhotoId, x.ReporterUserId })
                .IsUnique()
                .HasDatabaseName("UX_photo_reports_photo_reporter");
        });

        modelBuilder.ApplySnakeCaseColumnNames();
    }
}
