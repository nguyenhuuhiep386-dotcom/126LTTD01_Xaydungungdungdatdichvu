using Microsoft.EntityFrameworkCore;
using Servio.Api.Data.Entities;

namespace Servio.Api.Data;

// Table added after the scaffold (personal data compliance, 03_privacy.sql). Kept in a hand-written partial file so
// `dotnet ef dbcontext scaffold --force` does not drop it; if a re-scaffold adds UserConsents, delete this file.
public partial class ServioDbContext
{
    public virtual DbSet<UserConsent> UserConsents { get; set; }

    private static void ConfigurePrivacy(ModelBuilder modelBuilder) =>
        modelBuilder.Entity<UserConsent>(entity =>
        {
            entity.ToTable("UserConsents");
            entity.HasKey(e => e.Id);
            entity.HasIndex(e => new { e.UserId, e.Purpose, e.CreatedAt }, "IX_UserConsents_User_Purpose");
            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.PolicyVersion).HasMaxLength(20).IsUnicode(false);
            entity.Property(e => e.IpAddress).HasMaxLength(45).IsUnicode(false);
            entity.Property(e => e.UserAgent).HasMaxLength(300);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())");
            entity.HasOne(d => d.User).WithMany().HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull).HasConstraintName("FK_UserConsents_Users");
        });
}
