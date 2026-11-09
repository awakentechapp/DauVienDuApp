using Microsoft.EntityFrameworkCore;
using SightseeingTravel.Modules.Identity.Domain;

namespace SightseeingTravel.Modules.Identity.Infrastructure;

public sealed class IdentityDbContext : DbContext
{
    public const string SchemaName = "identity";

    public IdentityDbContext(DbContextOptions<IdentityDbContext> options)
        : base(options)
    {
    }

    public DbSet<User> Users => Set<User>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        modelBuilder.HasDefaultSchema(SchemaName);

        modelBuilder.Entity<User>(builder =>
        {
            builder.ToTable("users");
            builder.HasKey(u => u.Id);

            builder.Property(u => u.Email)
                .HasColumnName("email")
                .HasMaxLength(255)
                .IsRequired();

            builder.HasIndex(u => u.Email)
                .IsUnique()
                .HasDatabaseName("UX_users_email");

            builder.Property(u => u.PasswordHash)
                .HasColumnName("password_hash")
                .HasMaxLength(512)
                .IsRequired();

            builder.Property(u => u.DisplayName)
                .HasColumnName("display_name")
                .HasMaxLength(150)
                .IsRequired();

            builder.Property(u => u.AvatarUrl)
                .HasColumnName("avatar_url")
                .HasMaxLength(1024);

            builder.Property(u => u.Role)
                .HasColumnName("role")
                .HasConversion<string>()
                .HasMaxLength(32)
                .IsRequired();

            builder.Property(u => u.PreferredLanguage)
                .HasColumnName("preferred_language")
                .HasMaxLength(10)
                .IsRequired();

            builder.Property(u => u.IsActive)
                .HasColumnName("is_active")
                .IsRequired();

            builder.Property(u => u.CreatedAt)
                .HasColumnName("created_at")
                .IsRequired();

            builder.Property(u => u.UpdatedAt)
                .HasColumnName("updated_at")
                .IsRequired();

            builder.HasMany(u => u.RefreshTokens)
                .WithOne()
                .HasForeignKey(rt => rt.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<RefreshToken>(builder =>
        {
            builder.ToTable("refresh_tokens");
            builder.HasKey(rt => rt.Id);

            builder.Property(rt => rt.UserId)
                .HasColumnName("user_id")
                .IsRequired();

            builder.Property(rt => rt.TokenHash)
                .HasColumnName("token_hash")
                .HasMaxLength(256)
                .IsRequired();

            builder.HasIndex(rt => rt.TokenHash)
                .IsUnique()
                .HasDatabaseName("IX_refresh_tokens_token_hash");

            builder.Property(rt => rt.ExpiresAt)
                .HasColumnName("expires_at")
                .IsRequired();

            builder.Property(rt => rt.RevokedAt)
                .HasColumnName("revoked_at");

            builder.Property(rt => rt.CreatedAt)
                .HasColumnName("created_at")
                .IsRequired();

            builder.Ignore(rt => rt.IsActive);
        });
    }
}
