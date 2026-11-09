using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.Attractions.Domain;

namespace SightseeingTravel.Modules.Attractions.Infrastructure;

public sealed class AttractionsDbContext : DbContext
{
    public const string SchemaName = "attractions";

    public AttractionsDbContext(DbContextOptions<AttractionsDbContext> options)
        : base(options)
    {
    }

    public DbSet<PointOfInterest> PointsOfInterest => Set<PointOfInterest>();
    public DbSet<PoiLocalization> PoiLocalizations => Set<PoiLocalization>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        modelBuilder.HasDefaultSchema(SchemaName);

        modelBuilder.Entity<PointOfInterest>(entity =>
        {
            entity.ToTable("points_of_interest", SchemaName);
            entity.HasKey(x => x.Id);

            entity.Property(x => x.Code)
                .HasMaxLength(64)
                .IsRequired();

            entity.Property(x => x.Latitude)
                .IsRequired();

            entity.Property(x => x.Longitude)
                .IsRequired();

            entity.Property(x => x.ActivationRadiusMeters)
                .IsRequired();

            entity.Property(x => x.CoverImageUrl)
                .HasMaxLength(1024);

            entity.Property(x => x.CoverObjectKey)
                .HasMaxLength(512);

            entity.Property(x => x.Status)
                .HasConversion<string>()
                .HasMaxLength(32)
                .IsRequired();

            entity.Property(x => x.RejectionReason)
                .HasMaxLength(1000);

            entity.Property(x => x.CreatedBy)
                .IsRequired();

            entity.Property(x => x.CreatedAt)
                .IsRequired();

            entity.Property(x => x.UpdatedAt)
                .IsRequired();

            entity.HasIndex(x => x.Code)
                .IsUnique()
                .HasDatabaseName("UX_points_of_interest_code");

            entity.HasIndex(x => new { x.Status, x.Latitude, x.Longitude })
                .HasDatabaseName("IX_points_of_interest_status_geo");

            entity.HasMany(x => x.Localizations)
                .WithOne()
                .HasForeignKey(x => x.PoiId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<PoiLocalization>(entity =>
        {
            entity.ToTable("poi_localizations", SchemaName);
            entity.HasKey(x => x.Id);

            entity.Property(x => x.PoiId)
                .IsRequired();

            entity.Property(x => x.LanguageCode)
                .HasMaxLength(10)
                .IsRequired();

            entity.Property(x => x.Name)
                .HasMaxLength(200)
                .IsRequired();

            entity.Property(x => x.ShortSummary)
                .HasMaxLength(500)
                .IsRequired();

            entity.Property(x => x.CulturalStoryMarkdown)
                .IsRequired();

            entity.Property(x => x.HistoricalPeriod)
                .HasMaxLength(200);

            entity.HasIndex(x => new { x.PoiId, x.LanguageCode })
                .IsUnique()
                .HasDatabaseName("UX_poi_localizations_poi_lang");
        });
    }
}
