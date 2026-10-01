using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Admin;

/// <summary>Creates the first SUPER_ADMIN from configuration "SeedAdmin" when the AdminUsers table is empty.</summary>
public static class AdminSeeder
{
    public static async Task SeedAsync(IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var config = scope.ServiceProvider.GetRequiredService<IConfiguration>();
        var email = config["SeedAdmin:Email"];
        var password = config["SeedAdmin:Password"];
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrWhiteSpace(password))
        {
            return;
        }

        var db = scope.ServiceProvider.GetRequiredService<ServioDbContext>();
        if (await db.AdminUsers.AnyAsync())
        {
            return;
        }

        var now = DateTimeOffset.UtcNow;
        var admin = new AdminUser
        {
            Id = Guid.CreateVersion7(),
            Email = email,
            FullName = "Quản trị viên",
            Role = (byte)AdminRole.SuperAdmin,
            IsActive = true,
            MustChangePassword = true,
            CreatedAt = now,
            UpdatedAt = now,
        };
        admin.PasswordHash = new PasswordHasher<AdminUser>().HashPassword(admin, password);
        db.AdminUsers.Add(admin);
        await db.SaveChangesAsync();

        scope.ServiceProvider.GetRequiredService<ILoggerFactory>()
            .CreateLogger(nameof(AdminSeeder))
            .LogInformation("Seeded admin account {Email}", email);
    }
}
