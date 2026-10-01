using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Time.Testing;
using Microsoft.IdentityModel.JsonWebTokens;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Services.Auth;
using Servio.Api.Services.Users;

namespace Servio.Tests.Services;

/// <summary>
/// Reference test for services: EF Core InMemory database + FakeTimeProvider, Arrange/Act/Assert.
/// InMemory does not support ExecuteUpdate/transactions; test those paths against SQL Server manually.
/// </summary>
public sealed class AuthServiceTests
{
    private const string FixedCode = "123456";
    private readonly FakeTimeProvider _clock = new(new DateTimeOffset(2026, 10, 1, 8, 0, 0, TimeSpan.Zero));
    private readonly ServioDbContext _db = new(new DbContextOptionsBuilder<ServioDbContext>()
        .UseInMemoryDatabase(Guid.NewGuid().ToString())
        .AddInterceptors(new InMemoryRowVersionInterceptor())
        .Options);

    private AuthService CreateService(string? fixedCode = FixedCode)
    {
        var jwt = Options.Create(new JwtOptions { SigningKey = new string('k', 40) });
        var tokens = new TokenService(jwt, _clock);
        return new AuthService(
            _db,
            tokens,
            new UserService(_db, _clock),
            Options.Create(new OtpOptions { FixedCode = fixedCode }),
            _clock,
            new FakeHostEnvironment(),
            NullLogger<AuthService>.Instance);
    }

    private static OtpVerifyRequest Verify(Guid otpId, string code, AppFlavor flavor = AppFlavor.Customer) =>
        new(otpId, code, "device-1", null, flavor);

    [Fact]
    public async Task VerifyOtp_CreatesUserWithCustomerProfileAndWallet_OnFirstLogin()
    {
        // Arrange
        var auth = CreateService();
        var otp = await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default);

        // Act
        var result = await auth.VerifyOtpAsync(Verify(otp.OtpId, FixedCode), default);

        // Assert
        Assert.True(result.IsNewUser);
        Assert.True(result.NeedsProfileCompletion);
        Assert.Equal("+84901234567", result.User.PhoneNumber);
        Assert.Equal([UserRoleType.Customer], result.User.Roles);
        Assert.NotNull(result.User.CustomerProfile);
        Assert.Null(result.User.PartnerProfile);
        Assert.Equal(1, await _db.Wallets.CountAsync());

        var jwt = new JsonWebToken(result.AccessToken);
        Assert.Equal(result.User.Id.ToString(), jwt.Subject);
        Assert.Equal("Customer", jwt.GetClaim(TokenService.FlavorClaim).Value);
    }

    [Fact]
    public async Task VerifyOtp_AddsPartnerRoleToExistingUser_WithoutCreatingSecondUser()
    {
        // Arrange
        var auth = CreateService();
        var first = await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default);
        await auth.VerifyOtpAsync(Verify(first.OtpId, FixedCode), default);
        _clock.Advance(TimeSpan.FromMinutes(2));
        var second = await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Partner), default);

        // Act
        var result = await auth.VerifyOtpAsync(Verify(second.OtpId, FixedCode, AppFlavor.Partner), default);

        // Assert
        Assert.False(result.IsNewUser);
        Assert.True(result.IsNewRole);
        Assert.Equal([UserRoleType.Customer, UserRoleType.Partner], result.User.Roles);
        Assert.Equal(PartnerVerificationStatus.NotSubmitted, result.User.PartnerProfile!.VerificationStatus);
        Assert.Equal(1, await _db.Users.CountAsync());
        // The partner-app token carries only the Partner role, never Customer.
        Assert.Equal(["Partner"], new JsonWebToken(result.AccessToken).Claims.Where(c => c.Type == TokenService.RoleClaim).Select(c => c.Value));
    }

    [Theory]
    [InlineData((AppFlavor)0)]
    [InlineData((AppFlavor)99)]
    public async Task RequestOtp_RejectsUndefinedAppFlavor(AppFlavor flavor)
    {
        var auth = CreateService();

        var error = await Assert.ThrowsAsync<ApiException>(() =>
            auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, flavor), default));

        Assert.Equal(ErrorCodes.ValidationError, error.Code);
        Assert.Equal("appFlavor", error.Field);
    }

    [Fact]
    public async Task VerifyOtp_RejectsWrongCode_AndCountsTheAttempt()
    {
        var auth = CreateService();
        var otp = await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default);

        var error = await Assert.ThrowsAsync<ApiException>(() => auth.VerifyOtpAsync(Verify(otp.OtpId, "000000"), default));

        Assert.Equal(ErrorCodes.OtpInvalid, error.Code);
        Assert.Equal(1, (await _db.OtpCodes.SingleAsync()).AttemptCount);
    }

    [Fact]
    public async Task VerifyOtp_RejectsExpiredCode()
    {
        var auth = CreateService();
        var otp = await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default);
        _clock.Advance(TimeSpan.FromMinutes(6));

        var error = await Assert.ThrowsAsync<ApiException>(() => auth.VerifyOtpAsync(Verify(otp.OtpId, FixedCode), default));

        Assert.Equal(ErrorCodes.OtpExpired, error.Code);
    }

    [Fact]
    public async Task RequestOtp_IsRateLimited_WithinResendWindow()
    {
        var auth = CreateService();
        await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default);

        var error = await Assert.ThrowsAsync<ApiException>(() =>
            auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default));

        Assert.Equal(ErrorCodes.OtpRateLimited, error.Code);
    }

    [Fact]
    public async Task Refresh_RotatesToken_AndRejectsTheOldOne()
    {
        var auth = CreateService();
        var otp = await auth.RequestOtpAsync(new OtpRequest("0901234567", OtpPurpose.Login, AppFlavor.Customer), default);
        var login = await auth.VerifyOtpAsync(Verify(otp.OtpId, FixedCode), default);

        var refreshed = await auth.RefreshAsync(new RefreshRequest(login.RefreshToken), default);
        var reuse = await Assert.ThrowsAsync<ApiException>(() => auth.RefreshAsync(new RefreshRequest(login.RefreshToken), default));

        Assert.NotEqual(login.RefreshToken, refreshed.RefreshToken);
        Assert.Equal(ErrorCodes.RefreshTokenInvalid, reuse.Code);
    }

    private sealed class FakeHostEnvironment : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = "Development";
        public string ApplicationName { get; set; } = "Servio.Api";
        public string ContentRootPath { get; set; } = "";
        public Microsoft.Extensions.FileProviders.IFileProvider ContentRootFileProvider { get; set; } = null!;
    }
}
