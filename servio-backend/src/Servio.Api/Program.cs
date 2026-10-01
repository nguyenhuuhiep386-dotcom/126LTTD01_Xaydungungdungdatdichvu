using System.Security.Claims;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using Microsoft.OpenApi;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Services;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Auth;
using Servio.Api.Services.Catalog;
using Servio.Api.Services.Files;
using Servio.Api.Services.Partners;
using Servio.Api.Services.Users;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;
var isDemoEnvironment = builder.Environment.IsDevelopment() || builder.Environment.IsEnvironment("Demo");

// ---------- Options (validated at startup) ----------
var jwt = config.GetSection(JwtOptions.Section).Get<JwtOptions>() ?? new JwtOptions();
if (jwt.SigningKey.Length < 32)
{
    throw new InvalidOperationException("Jwt:SigningKey must be at least 32 characters. Set it in appsettings.Development.json or env var Jwt__SigningKey.");
}
if (!string.IsNullOrEmpty(config["Otp:FixedCode"]) && !isDemoEnvironment)
{
    throw new InvalidOperationException("Otp:FixedCode is only allowed in Development/Demo environments.");
}
builder.Services.Configure<JwtOptions>(config.GetSection(JwtOptions.Section));
builder.Services.Configure<OtpOptions>(config.GetSection(OtpOptions.Section));
builder.Services.Configure<FileStorageOptions>(config.GetSection(FileStorageOptions.Section));
builder.Services.AddSingleton(TimeProvider.System);

// ---------- Database ----------
builder.Services.AddDbContext<ServioDbContext>(options => options.UseSqlServer(
    config.GetConnectionString("Servio") ?? throw new InvalidOperationException("ConnectionStrings:Servio is missing."),
    sql => sql.UseCompatibilityLevel(config.GetValue("Database:CompatibilityLevel", 140)))); // 140 = SQL Server 2017

// ---------- Services (one class per module; register new ones here) ----------
builder.Services.AddScoped<TokenService>();
builder.Services.AddScoped<AuthService>();
builder.Services.AddScoped<UserService>();
builder.Services.AddScoped<CategoryService>();
builder.Services.AddScoped<SystemConfigService>();
builder.Services.AddScoped<FileService>();
builder.Services.AddScoped<AddressService>();
builder.Services.AddScoped<PartnerProfileService>();
builder.Services.AddScoped<PartnerSkillService>();
builder.Services.AddScoped<PartnerPublicService>();
builder.Services.AddScoped<PartnerVerificationService>();
builder.Services.AddScoped<AdminCategoryService>();
builder.Services.AddMemoryCache();

// ---------- Authentication: JWT for the apps, cookie for admin pages ----------
builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.MapInboundClaims = false;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = jwt.Issuer,
            ValidAudience = jwt.Audience,
            IssuerSigningKey = TokenService.SigningKey(jwt),
            NameClaimType = "sub",
            RoleClaimType = TokenService.RoleClaim,
            ClockSkew = TimeSpan.FromSeconds(30),
        };
        // SignalR hubs (added in later work packages) send the token as ?access_token=...
        options.Events = new JwtBearerEvents
        {
            OnMessageReceived = context =>
            {
                var token = context.Request.Query["access_token"];
                if (!string.IsNullOrEmpty(token) && context.HttpContext.Request.Path.StartsWithSegments("/hubs"))
                {
                    context.Token = token;
                }
                return Task.CompletedTask;
            },
            OnChallenge = async context =>
            {
                context.HandleResponse();
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                var error = new ApiError(ErrorCodes.Unauthenticated, null, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
                await context.Response.WriteAsJsonAsync(ApiResponse.Fail(context.HttpContext, error.Message, [error]));
            },
            OnForbidden = async context =>
            {
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                var error = new ApiError(ErrorCodes.Forbidden, null, "Bạn không có quyền thực hiện thao tác này");
                await context.Response.WriteAsJsonAsync(ApiResponse.Fail(context.HttpContext, error.Message, [error]));
            },
        };
    })
    .AddCookie(AuthPolicies.AdminScheme, options =>
    {
        options.LoginPath = "/admin/login";
        options.LogoutPath = "/admin/logout";
        options.AccessDeniedPath = "/admin/denied";
        options.Cookie.Name = "servio.admin";
        options.Cookie.HttpOnly = true;
        options.Cookie.SameSite = SameSiteMode.Strict;
        options.ExpireTimeSpan = TimeSpan.FromHours(8);
        options.SlidingExpiration = true;
        // A cookie of a deactivated or deleted admin (e.g. after re-running 01_schema.sql) must stop working at once.
        options.Events.OnValidatePrincipal = async context =>
        {
            var id = context.Principal?.FindFirstValue(ClaimTypes.NameIdentifier);
            var db = context.HttpContext.RequestServices.GetRequiredService<ServioDbContext>();
            var active = Guid.TryParse(id, out var adminId) &&
                         await db.AdminUsers.AnyAsync(a => a.Id == adminId && a.IsActive, context.HttpContext.RequestAborted);
            if (!active)
            {
                context.RejectPrincipal();
                await context.HttpContext.SignOutAsync(AuthPolicies.AdminScheme);
            }
        };
    });

builder.Services.AddAuthorizationBuilder()
    .AddPolicy(AuthPolicies.Customer, p => p.RequireRole(nameof(UserRoleType.Customer)))
    .AddPolicy(AuthPolicies.Partner, p => p.RequireRole(nameof(UserRoleType.Partner)))
    .AddPolicy(AuthPolicies.Admin, p => p.AddAuthenticationSchemes(AuthPolicies.AdminScheme).RequireAuthenticatedUser())
    .AddPolicy(AuthPolicies.AdminOperator, p => p.AddAuthenticationSchemes(AuthPolicies.AdminScheme)
        .RequireRole(nameof(AdminRole.SuperAdmin), nameof(AdminRole.Operator)));

// ---------- Rate limiting ----------
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.AddPolicy(RateLimitPolicies.Otp, context => RateLimitPartition.GetFixedWindowLimiter(
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(1) }));
    options.AddPolicy(RateLimitPolicies.AdminLogin, context => context.Request.Method == HttpMethods.Post
        ? RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 5, Window = TimeSpan.FromMinutes(1) })
        : RateLimitPartition.GetNoLimiter("get"));
});

// ---------- MVC, JSON, Razor Pages (admin) ----------
builder.Services
    // Only explicit [Required] counts; otherwise a bad body also reports "The request field is required".
    .AddControllers(options => options.SuppressImplicitRequiredAttributeForNonNullableReferenceTypes = true)
    .AddJsonOptions(options =>
    {
        options.JsonSerializerOptions.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseUpper));
        options.JsonSerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.Never;
    })
    .ConfigureApiBehaviorOptions(options => options.InvalidModelStateResponseFactory = ApiExceptionHandler.InvalidModelState);

builder.Services.Configure<RouteOptions>(options => options.LowercaseUrls = true);
builder.Services.AddRazorPages(options =>
{
    options.Conventions.AuthorizeFolder("/Admin", AuthPolicies.Admin);
    options.Conventions.AllowAnonymousToPage("/Admin/Login");
});

builder.Services.AddExceptionHandler<ApiExceptionHandler>();
builder.Services.AddProblemDetails();
builder.Services.AddHealthChecks();

builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new OpenApiInfo { Title = "Servio API", Version = "v1" });
    options.AddSecurityDefinition("Bearer", new OpenApiSecurityScheme
    {
        Type = SecuritySchemeType.Http,
        Scheme = "bearer",
        BearerFormat = "JWT",
        Description = "Paste the accessToken from POST /api/v1/auth/otp/verify",
    });
    options.AddSecurityRequirement(document => new OpenApiSecurityRequirement
    {
        [new OpenApiSecuritySchemeReference("Bearer", document)] = [],
    });
    var xml = Path.Combine(AppContext.BaseDirectory, "Servio.Api.xml");
    if (File.Exists(xml))
    {
        options.IncludeXmlComments(xml);
    }
});

var app = builder.Build();

app.UseExceptionHandler();
if (isDemoEnvironment)
{
    app.UseSwagger();
    app.UseSwaggerUI(options => options.DocumentTitle = "Servio API");
}

app.UseStaticFiles();
app.UseRouting();
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();

app.MapHealthChecks("/health/live");
app.MapControllers();
app.MapRazorPages();
app.MapGet("/", () => Results.Redirect(isDemoEnvironment ? "/swagger" : "/admin")).ExcludeFromDescription();

await AdminSeeder.SeedAsync(app.Services);
await app.RunAsync();

public partial class Program;
