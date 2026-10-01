using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Time.Testing;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services;
using Servio.Api.Services.Files;
using Servio.Api.Services.Users;

namespace Servio.Tests;

/// <summary>
/// Shared setup for service tests: EF Core InMemory database, fixed clock and a temp folder for uploads.
/// Create one per test (xUnit creates a new test class instance per test).
/// </summary>
public sealed class TestContext : IDisposable
{
    public FakeTimeProvider Clock { get; } = new(new DateTimeOffset(2026, 10, 1, 8, 0, 0, TimeSpan.Zero));
    public ServioDbContext Db { get; }
    public string RootPath { get; } = Path.Combine(Path.GetTempPath(), "servio-tests", Guid.NewGuid().ToString("N"));

    public TestContext()
    {
        Db = new ServioDbContext(new DbContextOptionsBuilder<ServioDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString())
            .ConfigureWarnings(w => w.Ignore(InMemoryEventId.TransactionIgnoredWarning))
            .AddInterceptors(new InMemoryRowVersionInterceptor())
            .Options);
        Directory.CreateDirectory(Path.Combine(RootPath, "wwwroot"));
    }

    public FileService Files() => new(Db, new FakeWebHostEnvironment(RootPath), Options.Create(new FileStorageOptions()), Clock);

    public UserService Users() => new(Db, Files(), Clock);

    public SystemConfigService Configs() => new(Db, new MemoryCache(new MemoryCacheOptions()));

    /// <summary>Adds a user with the given role and profile. Returns the user.</summary>
    public async Task<User> AddUserAsync(UserRoleType role, string fullName = "Nguyễn Văn An", string phone = "+84901234567")
    {
        var now = Clock.GetUtcNow();
        var user = new User { Id = Guid.NewGuid(), PhoneNumber = phone, FullName = fullName, Status = (byte)UserStatus.Active, CreatedAt = now, UpdatedAt = now };
        user.UserRoles.Add(new UserRole { Id = Guid.NewGuid(), Role = (byte)role, CreatedAt = now });
        if (role == UserRoleType.Customer)
        {
            user.CustomerProfile = new CustomerProfile { Id = Guid.NewGuid(), CreatedAt = now, UpdatedAt = now };
        }
        else
        {
            user.PartnerProfile = new PartnerProfile { Id = Guid.NewGuid(), ServiceRadiusKm = 10, CreatedAt = now, UpdatedAt = now };
        }
        Db.Users.Add(user);
        await Db.SaveChangesAsync();
        return user;
    }

    public void Dispose()
    {
        Db.Dispose();
        if (Directory.Exists(RootPath))
        {
            Directory.Delete(RootPath, recursive: true);
        }
    }

    private sealed class FakeWebHostEnvironment(string root) : IWebHostEnvironment
    {
        public string WebRootPath { get; set; } = Path.Combine(root, "wwwroot");
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
        public string EnvironmentName { get; set; } = "Development";
        public string ApplicationName { get; set; } = "Servio.Api";
        public string ContentRootPath { get; set; } = root;
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    }
}
