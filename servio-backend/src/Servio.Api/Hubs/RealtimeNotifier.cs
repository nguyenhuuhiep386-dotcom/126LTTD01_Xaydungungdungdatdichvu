using Microsoft.AspNetCore.SignalR;
using Servio.Api.Common;
using Servio.Api.Services.Chat;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Quotes;

namespace Servio.Api.Hubs;

/// <summary>
/// Sends realtime events after the database commit (spec 0.2.7 ý 12: no outbox). Services depend on this interface
/// so unit tests can record the events instead of needing SignalR.
/// </summary>
public interface IRealtimeNotifier
{
    Task NewPostAsync(IReadOnlyList<(Guid PartnerUserId, FeedItemDto Item)> recipients, CancellationToken ct);
    Task NewQuoteAsync(Guid customerUserId, CustomerQuoteDto quote, CancellationToken ct);
    Task QuoteWithdrawnAsync(Guid customerUserId, Guid serviceRequestId, Guid quoteId, CancellationToken ct);

    /// <summary>/hubs/chat ReceiveMessage to both members (all their connections of the matching app).</summary>
    Task MessageReceivedAsync(Guid customerUserId, Guid partnerUserId, MessageDto message, CancellationToken ct);

    Task MessageReadAsync(Guid customerUserId, Guid partnerUserId, MessageRead read, CancellationToken ct);
}

public sealed class SignalRNotifier(
    IHubContext<FeedHub> feed,
    IHubContext<OrdersHub> orders,
    IHubContext<ChatHub> chat,
    ILogger<SignalRNotifier> logger) : IRealtimeNotifier
{
    public Task NewPostAsync(IReadOnlyList<(Guid PartnerUserId, FeedItemDto Item)> recipients, CancellationToken ct) =>
        SafeAsync(RealtimeEvents.NewPost, () => Task.WhenAll(recipients.Select(r =>
            feed.Clients.Group(RealtimeGroups.For(AppFlavor.Partner, r.PartnerUserId)).SendAsync(RealtimeEvents.NewPost, r.Item, ct))));

    public Task NewQuoteAsync(Guid customerUserId, CustomerQuoteDto quote, CancellationToken ct) =>
        SafeAsync(RealtimeEvents.NewQuote, () =>
            orders.Clients.Group(RealtimeGroups.For(AppFlavor.Customer, customerUserId)).SendAsync(RealtimeEvents.NewQuote, quote, ct));

    public Task QuoteWithdrawnAsync(Guid customerUserId, Guid serviceRequestId, Guid quoteId, CancellationToken ct) =>
        SafeAsync(RealtimeEvents.QuoteWithdrawn, () =>
            orders.Clients.Group(RealtimeGroups.For(AppFlavor.Customer, customerUserId))
                .SendAsync(RealtimeEvents.QuoteWithdrawn, new { serviceRequestId, quoteId }, ct));

    public Task MessageReceivedAsync(Guid customerUserId, Guid partnerUserId, MessageDto message, CancellationToken ct) =>
        SafeAsync(ChatHub.ReceiveMessage, () => ToMembers(customerUserId, partnerUserId).SendAsync(ChatHub.ReceiveMessage, message, ct));

    public Task MessageReadAsync(Guid customerUserId, Guid partnerUserId, MessageRead read, CancellationToken ct) =>
        SafeAsync(ChatHub.MessageReadEvent, () => ToMembers(customerUserId, partnerUserId).SendAsync(ChatHub.MessageReadEvent, read, ct));

    private IClientProxy ToMembers(Guid customerUserId, Guid partnerUserId) =>
        chat.Clients.Groups(RealtimeGroups.For(AppFlavor.Customer, customerUserId), RealtimeGroups.For(AppFlavor.Partner, partnerUserId));

    /// <summary>The change is already saved; a failed push only means the client refreshes later, so log and continue.</summary>
    private async Task SafeAsync(string eventName, Func<Task> send)
    {
        try
        {
            await send();
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            logger.LogWarning(e, "Realtime event {Event} failed", eventName);
        }
    }
}
