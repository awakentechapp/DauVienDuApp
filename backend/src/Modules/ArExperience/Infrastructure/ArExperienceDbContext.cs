using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.ArExperience.Domain;
using SightseeingTravel.Persistence;

namespace SightseeingTravel.Modules.ArExperience.Infrastructure;

public sealed class ArExperienceDbContext : DbContext
{
    public const string SchemaName = "ar";

    public DbSet<ArInfoCard> ArInfoCards => Set<ArInfoCard>();
    public DbSet<ArInfoCardLocalization> ArInfoCardLocalizations => Set<ArInfoCardLocalization>();
    public DbSet<ArModelAsset> ArModelAssets => Set<ArModelAsset>();

    public ArExperienceDbContext(DbContextOptions<ArExperienceDbContext> options)
        : base(options)
    {
    }

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        modelBuilder.HasDefaultSchema(SchemaName);

        modelBuilder.Entity<ArInfoCard>(builder =>
        {
            builder.ToTable("ar_info_cards", SchemaName);
            builder.HasKey(x => x.Id);
            builder.Property(x => x.Id).ValueGeneratedNever();

            builder.Property(x => x.PoiId)
                .IsRequired();

            builder.Property(x => x.CardType)
                .HasConversion<string>()
                .HasMaxLength(32)
                .IsRequired();

            builder.Property(x => x.AzimuthDegrees)
                .IsRequired();

            builder.Property(x => x.DistanceMeters)
                .IsRequired();

            builder.Property(x => x.ElevationMeters)
                .IsRequired();

            builder.Property(x => x.DisplayOrder)
                .IsRequired();

            builder.Property(x => x.IsActive)
                .IsRequired();

            builder.Property(x => x.ModelObjectKey)
                .HasMaxLength(512);

            builder.Property(x => x.ModelName)
                .HasMaxLength(200);

            builder.Property(x => x.ModelCategory)
                .HasConversion<string>()
                .HasMaxLength(20)
                .IsRequired();

            builder.Property(x => x.ModelStatus)
                .HasMaxLength(20)
                .IsRequired();

            builder.Property(x => x.ModelError)
                .HasMaxLength(500);

            builder.Property(x => x.NodeOdmTaskId)
                .HasMaxLength(128);

            builder.Property(x => x.ModelRotationDegrees)
                .IsRequired();

            builder.Property(x => x.CreatedAt)
                .IsRequired();

            builder.Property(x => x.UpdatedAt)
                .IsRequired();

            builder.HasIndex(x => new { x.PoiId, x.IsActive, x.DisplayOrder })
                .HasDatabaseName("IX_ar_info_cards_poi_active");

            builder.HasMany(x => x.Localizations)
                .WithOne()
                .HasForeignKey(x => x.ArInfoCardId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<ArInfoCardLocalization>(builder =>
        {
            builder.ToTable("ar_info_card_localizations", SchemaName);
            builder.HasKey(x => x.Id);
            builder.Property(x => x.Id).ValueGeneratedNever();

            builder.Property(x => x.ArInfoCardId)
                .IsRequired();

            builder.Property(x => x.LanguageCode)
                .HasMaxLength(10)
                .IsRequired();

            builder.Property(x => x.Title)
                .HasMaxLength(200)
                .IsRequired();

            builder.Property(x => x.Subtitle)
                .HasMaxLength(300);

            builder.Property(x => x.Content)
                .HasMaxLength(2000)
                .IsRequired();

            builder.HasIndex(x => new { x.ArInfoCardId, x.LanguageCode })
                .IsUnique()
                .HasDatabaseName("UX_ar_card_localizations_card_lang");
        });

        modelBuilder.Entity<ArModelAsset>(builder =>
        {
            builder.ToTable("ar_model_assets", SchemaName);
            builder.HasKey(x => x.Id);
            builder.Property(x => x.Id).ValueGeneratedNever();
            builder.Property(x => x.Name).HasMaxLength(200).IsRequired();
            builder.Property(x => x.Category).HasConversion<string>().HasMaxLength(20).IsRequired();
            builder.Property(x => x.ModelObjectKey).HasMaxLength(512);
            builder.Property(x => x.ModelStatus).HasMaxLength(20).IsRequired();
            builder.Property(x => x.ModelError).HasMaxLength(500);
            builder.Property(x => x.NodeOdmTaskId).HasMaxLength(128);
            builder.Property(x => x.ModelRotationDegrees).IsRequired();
            builder.Property(x => x.CreatedAt).IsRequired();
            builder.Property(x => x.UpdatedAt).IsRequired();
            builder.HasIndex(x => x.ModelStatus).HasDatabaseName("IX_ar_model_assets_status");
            builder.HasIndex(x => x.ModelObjectKey).HasDatabaseName("IX_ar_model_assets_object_key");
        });

        modelBuilder.ApplySnakeCaseColumnNames();
    }
}
