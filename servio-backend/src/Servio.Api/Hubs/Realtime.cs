using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.IdentityModel.JsonWebTokens;
using Servio.Api.Common;
using Servio.Api.Services.Auth;

namespace Servio.Api.Hubs;

/// <summary>
/// Names of server → client events. Android listens with <c>connection.on("NewPost", ...)</c>.
/// Payloads are the same JSON as the REST DTOs (camelCase, enums as UPPER_SNAKE_CASE).
/// </summary>
public static class RealtimeEvents
{
    /// <summary>/hubs/feed → partner: a new post matches you. Payload: FeedItemDto.</summary>
    public const string NewPost = "NewPost";

    /// <summary>/hubs/orders → customer: a partner quoted your post. Payload: CustomerQuoteDto.</summary>
    public const string NewQuote = "NewQuote";

    /// <summary>/hubs/orders → customer: a quote was withdrawn. Payload: { serviceRequestId, quoteId }.</summary>
    public const string QuoteWithdrawn = "QuoteWithdrawn";
}

/// <summary>Groups are per user and per app, so a user who has both apps only gets the events of each app in that app.</summary>
public static class RealtimeGroups
{
    public static string For(AppFlavor flavor, Guid userId) => $"{flavor.ToString().ToLowerInvariant()}:{userId}";
}

/// <summary>
/// /hubs/feed — partner app only. Connect with <c>?access_token=&lt;JWT&gt;</c>; the connection joins
/// <c>partner:{userId}</c> and receives <see cref="RealtimeEvents.NewPost"/>.
/// </summary>
[Authorize(Policy = AuthPolicies.Partner)]
public sealed class FeedHub : Hub
{
    public override async Task OnConnectedAsync()
    {
        await Groups.AddToGroupAsync(Context.ConnectionId, RealtimeGroups.For(AppFlavor.Partner, Context.User.UserId()));
        await base.OnConnectedAsync();
    }
}

/// <summary>
/// /hubs/orders — both apps. Joins <c>customer:{userId}</c> or <c>partner:{userId}</c> by the token's app.
/// BE-2 sends quote events here; order status and tracking (BE-4/BE-5) will use the same hub.
/// </summary>
[Authorize]
public sealed class OrdersHub : Hub
{
    public override async Task OnConnectedAsync()
    {
        var flavor = Enum.Parse<AppFlavor>(Context.User!.FindFirst(TokenService.FlavorClaim)!.Value);
        await Groups.AddToGroupAsync(Context.ConnectionId, RealtimeGroups.For(flavor, Context.User.UserId()));
        await base.OnConnectedAsync();
    }
}

internal static class HubUserExtensions
{
    public static Guid UserId(this System.Security.Claims.ClaimsPrincipal? user) =>
        Guid.Parse(user?.FindFirst(JwtRegisteredClaimNames.Sub)?.Value ?? throw new HubException("Unauthenticated"));
}
