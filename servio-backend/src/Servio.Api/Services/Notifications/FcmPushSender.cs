using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace Servio.Api.Services.Notifications;

public sealed class FcmOptions
{
    public const string Section = "Fcm";

    /// <summary>Path of the Firebase service-account JSON (Project settings → Service accounts). Empty = push disabled.</summary>
    public string ServiceAccountFile { get; set; } = "";
}

/// <summary>Sends one push. Returns false when the token is dead (uninstalled app) so the device can be deactivated.</summary>
public interface IPushSender
{
    Task<bool> SendAsync(string token, string title, string body, IReadOnlyDictionary<string, string> data, CancellationToken ct);
}

/// <summary>
/// FCM HTTP v1 (spec 0.2.2 row 8) without the Firebase SDK: sign a service-account JWT, swap it for an OAuth token
/// (cached ~55 minutes), POST messages:send. Push is best effort (0.2.7 ý 12): errors are logged, the inbox stays right.
/// </summary>
public sealed class FcmPushSender(HttpClient http, IOptions<FcmOptions> options, ILogger<FcmPushSender> logger) : IPushSender
{
    private readonly SemaphoreSlim _lock = new(1, 1);
    private ServiceAccount? _account;
    private string? _token;
    private DateTimeOffset _tokenExpiresAt;

    public async Task<bool> SendAsync(string token, string title, string body, IReadOnlyDictionary<string, string> data, CancellationToken ct)
    {
        if (string.IsNullOrEmpty(options.Value.ServiceAccountFile))
        {
            logger.LogDebug("FCM disabled (Fcm:ServiceAccountFile empty): {Title}", title);
            return true;
        }
        var account = await AccountAsync(ct);
        using var request = new HttpRequestMessage(HttpMethod.Post, $"https://fcm.googleapis.com/v1/projects/{account.ProjectId}/messages:send")
        {
            Content = JsonContent.Create(new
            {
                message = new
                {
                    token,
                    notification = new { title, body },
                    data,
                    android = new { priority = "high" },
                },
            }),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await AccessTokenAsync(account, ct));
        using var response = await http.SendAsync(request, ct);
        if (response.IsSuccessStatusCode)
        {
            return true;
        }
        var error = await response.Content.ReadAsStringAsync(ct);
        // 404 UNREGISTERED / 400 INVALID_ARGUMENT on the token: the app was uninstalled or the token is garbage.
        if (response.StatusCode == HttpStatusCode.NotFound || error.Contains("UNREGISTERED", StringComparison.Ordinal))
        {
            return false;
        }
        logger.LogWarning("FCM send failed {Status}: {Error}", (int)response.StatusCode, error);
        return true;
    }

    private async Task<ServiceAccount> AccountAsync(CancellationToken ct) =>
        _account ??= JsonSerializer.Deserialize<ServiceAccount>(await File.ReadAllTextAsync(options.Value.ServiceAccountFile, ct))
                     ?? throw new InvalidOperationException("Invalid Firebase service-account file.");

    private async Task<string> AccessTokenAsync(ServiceAccount account, CancellationToken ct)
    {
        await _lock.WaitAsync(ct);
        try
        {
            if (_token is not null && DateTimeOffset.UtcNow < _tokenExpiresAt)
            {
                return _token;
            }
            using var rsa = RSA.Create();
            rsa.ImportFromPem(account.PrivateKey);
            var now = DateTime.UtcNow;
            var assertion = new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
            {
                Issuer = account.ClientEmail,
                Audience = account.TokenUri,
                IssuedAt = now,
                NotBefore = now,
                Expires = now.AddHours(1),
                Claims = new Dictionary<string, object> { ["scope"] = "https://www.googleapis.com/auth/firebase.messaging" },
                SigningCredentials = new SigningCredentials(new RsaSecurityKey(rsa) { KeyId = account.PrivateKeyId }, SecurityAlgorithms.RsaSha256),
            });
            using var response = await http.PostAsync(account.TokenUri, new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "urn:ietf:params:oauth:grant-type:jwt-bearer",
                ["assertion"] = assertion,
            }), ct);
            response.EnsureSuccessStatusCode();
            var result = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
            _token = result.GetProperty("access_token").GetString();
            _tokenExpiresAt = DateTimeOffset.UtcNow.AddSeconds(result.GetProperty("expires_in").GetInt32() - 300);
            return _token!;
        }
        finally
        {
            _lock.Release();
        }
    }

    private sealed record ServiceAccount(
        [property: System.Text.Json.Serialization.JsonPropertyName("project_id")] string ProjectId,
        [property: System.Text.Json.Serialization.JsonPropertyName("client_email")] string ClientEmail,
        [property: System.Text.Json.Serialization.JsonPropertyName("private_key_id")] string PrivateKeyId,
        [property: System.Text.Json.Serialization.JsonPropertyName("private_key")] string PrivateKey,
        [property: System.Text.Json.Serialization.JsonPropertyName("token_uri")] string TokenUri);
}
