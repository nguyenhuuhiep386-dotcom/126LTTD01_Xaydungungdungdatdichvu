using Microsoft.EntityFrameworkCore;
using Servio.Api.Data.Entities;

namespace Servio.Api.Data;

// Hand-written model tweaks. Kept in a separate partial file so `dotnet ef dbcontext scaffold --force` does not overwrite it.
public partial class ServioDbContext
{
    partial void OnModelCreatingPartial(ModelBuilder modelBuilder)
    {
        // Two parallel requests on the same OTP / refresh token: the second SaveChanges fails
        // (DbUpdateConcurrencyException → 409) instead of bypassing the attempt limit or minting two sessions.
        modelBuilder.Entity<OtpCode>(entity =>
        {
            entity.Property(e => e.AttemptCount).IsConcurrencyToken();
            entity.Property(e => e.IsUsed).IsConcurrencyToken();
        });
        modelBuilder.Entity<RefreshToken>().Property(e => e.RevokedAt).IsConcurrencyToken();
    }
}
