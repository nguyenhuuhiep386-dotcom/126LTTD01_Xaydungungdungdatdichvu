using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Pages.Admin;

/// <summary>AW-01 — admin sign-in with a cookie (separate from the apps' JWT).</summary>
[EnableRateLimiting(RateLimitPolicies.AdminLogin)]
public sealed class LoginModel(ServioDbContext db, TimeProvider clock) : PageModel
{
    [BindProperty, Required(ErrorMessage = "Nhập email"), EmailAddress(ErrorMessage = "Email không hợp lệ")]
    public string Email { get; set; } = "";

    [BindProperty, Required(ErrorMessage = "Nhập mật khẩu"), DataType(DataType.Password)]
    public string Password { get; set; } = "";

    public string? Error { get; private set; }

    private static readonly string DummyHash = new PasswordHasher<AdminUser>().HashPassword(new AdminUser(), Guid.NewGuid().ToString());

    public void OnGet()
    {
    }

    public async Task<IActionResult> OnPostAsync(string? returnUrl, CancellationToken ct)
    {
        if (!ModelState.IsValid)
        {
            return Page();
        }

        var admin = await db.AdminUsers.FirstOrDefaultAsync(a => a.Email == Email && a.IsActive, ct);
        // Hash even when the email is unknown so response time does not reveal which emails exist.
        var hasher = new PasswordHasher<AdminUser>();
        var verified = hasher.VerifyHashedPassword(admin ?? new AdminUser(), admin?.PasswordHash ?? DummyHash, Password)
                       != PasswordVerificationResult.Failed && admin is not null;
        if (!verified)
        {
            Error = "Email hoặc mật khẩu không đúng";
            return Page();
        }

        admin!.LastLoginAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);

        var identity = new ClaimsIdentity(
            [
                new Claim(ClaimTypes.NameIdentifier, admin.Id.ToString()),
                new Claim(ClaimTypes.Name, admin.FullName),
                new Claim(ClaimTypes.Email, admin.Email),
                new Claim(ClaimTypes.Role, ((AdminRole)admin.Role).ToString()),
            ],
            AuthPolicies.AdminScheme);
        await HttpContext.SignInAsync(AuthPolicies.AdminScheme, new ClaimsPrincipal(identity));

        return LocalRedirect(Url.IsLocalUrl(returnUrl) ? returnUrl : "/admin");
    }
}
