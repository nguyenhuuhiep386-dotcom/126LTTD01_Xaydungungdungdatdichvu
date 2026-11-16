using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.IdentityModel.JsonWebTokens;
using Servio.Api.Common;
using Servio.Api.Services.Auth;
using Servio.Api.Services.Orders;

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

    /// <summary>/hubs/orders → partner: the customer chose your quote and an order waits for you (#65). Payload: { quoteId, orderId }.</summary>
    public const string QuoteAccepted = "QuoteAccepted";

    /// <summary>/hubs/orders → partner: the customer chose someone else. Payload: { quoteId, orderId: null }.</summary>
    public const string QuoteRejected = "QuoteRejected";

    /// <summary>/hubs/orders → customer and partner of the order: a status changed. Payload: OrderStatusChangedDto.</summary>
    public const string OrderStatusChanged = "OrderStatusChanged";

    /// <summary>/hubs/orders → customer: the partner asks for an extra charge (CS-22). Payload: { orderId, extraChargeId, amount, description }.</summary>
    public const string ExtraChargeRequested = "ExtraChargeRequested";

    /// <summary>/hubs/orders → partner: the customer approved or rejected an extra charge. Payload: ExtraChargeDto.</summary>
    public const string ExtraChargeResponded = "ExtraChargeResponded";

    /// <summary>/hubs/orders → customer: new position of a partner on the way (CS-21). Payload: TrackingDto.</summary>
    public const string PartnerLocationUpdated = "PartnerLocationUpdated";

    /// <summary>/hubs/orders → customer: the partner says they arrived, please confirm (#178). Payload: ArrivalConfirmationDto.</summary>
    public const string ArrivalConfirmationRequested = "ArrivalConfirmationRequested";

    /// <summary>/hubs/orders → partner: the customer confirmed or denied (#179). Payload: ArrivalConfirmationDto.</summary>
    public const string ArrivalConfirmationResolved = "ArrivalConfirmationResolved";
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
/// Quote events (BE-2), order events (BE-4) and tracking (BE-5) are sent here.
///
/// Client → server (partner app only): <c>UpdateLocation(assignmentId, latitude, longitude, accuracy, capturedAt)</c>
/// every ~10 s while ON_THE_WAY; returns false when the point was older than the stored one. A HubException
/// "INVALID_STATUS_TRANSITION: ..." means the assignment left ON_THE_WAY: stop the foreground service.
/// </summary>
[Authorize]
public sealed class OrdersHub(TrackingService tracking) : Hub
{
    private AppFlavor Flavor => Enum.Parse<AppFlavor>(Context.User!.FindFirst(TokenService.FlavorClaim)!.Value);

    public override async Task OnConnectedAsync()
    {
        await Groups.AddToGroupAsync(Context.ConnectionId, RealtimeGroups.For(Flavor, Context.User.UserId()));
        await base.OnConnectedAsync();
    }

    public async Task<bool> UpdateLocation(Guid assignmentId, decimal latitude, decimal longitude, int? accuracy, DateTimeOffset capturedAt)
    {
        if (Flavor != AppFlavor.Partner)
        {
            throw new HubException($"{ErrorCodes.Forbidden}: Chỉ app đối tác gửi vị trí");
        }
        try
        {
            return await tracking.UpdateLocationAsync(Context.User.UserId(), assignmentId, latitude, longitude, accuracy, capturedAt, Context.ConnectionAborted);
        }
        catch (ApiException e)
        {
            throw new HubException($"{e.Code}: {e.Message}");
        }
    }
}

internal static class HubUserExtensions
{
    public static Guid UserId(this System.Security.Claims.ClaimsPrincipal? user) =>
        Guid.Parse(user?.FindFirst(JwtRegisteredClaimNames.Sub)?.Value ?? throw new HubException("Unauthenticated"));
}
