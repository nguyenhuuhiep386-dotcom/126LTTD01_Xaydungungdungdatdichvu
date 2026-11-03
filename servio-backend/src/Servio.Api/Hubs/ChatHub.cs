using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Servio.Api.Common;
using Servio.Api.Services.Auth;
using Servio.Api.Services.Chat;

namespace Servio.Api.Hubs;

/// <summary>
/// /hubs/chat — both apps (spec 0.2.4 M4). Each connection joins <c>customer:{userId}</c> or <c>partner:{userId}</c>,
/// so it receives ReceiveMessage / MessageRead for every conversation of that app without joining each one.
///
/// Client → server: <c>SendMessage(HubSendMessage)</c>, <c>MarkRead(conversationId, messageId)</c>,
/// <c>JoinConversation(conversationId)</c> (only checks access; kept for the spec contract).
/// Server → client: <c>ReceiveMessage(MessageDto)</c>, <c>MessageAck(MessageAck)</c> to the sender's connection,
/// <c>MessageRead(MessageRead)</c>. Business errors come back as HubException with the Vietnamese message.
/// </summary>
[Authorize]
public sealed class ChatHub(ChatService chat) : Hub
{
    public const string ReceiveMessage = "ReceiveMessage";
    public const string MessageAckEvent = "MessageAck";
    public const string MessageReadEvent = "MessageRead";

    private Guid UserId => Context.User.UserId();
    private AppFlavor Flavor => Enum.Parse<AppFlavor>(Context.User!.FindFirst(TokenService.FlavorClaim)!.Value);

    public override async Task OnConnectedAsync()
    {
        await Groups.AddToGroupAsync(Context.ConnectionId, RealtimeGroups.For(Flavor, UserId));
        await base.OnConnectedAsync();
    }

    public Task JoinConversation(Guid conversationId) =>
        Run(() => chat.GetAsync(UserId, Flavor, conversationId, Context.ConnectionAborted));

    public async Task SendMessage(HubSendMessage message)
    {
        var saved = await Run(() => chat.SendAsync(UserId, Flavor, message.ConversationId,
            new SendMessageRequest(message.ClientMessageId, message.Type, message.Content, message.AttachmentUrl), Context.ConnectionAborted));
        await Clients.Caller.SendAsync(MessageAckEvent, new MessageAck(saved.ClientMessageId, saved.Id, saved.CreatedAt), Context.ConnectionAborted);
    }

    public Task MarkRead(Guid conversationId, Guid messageId) =>
        Run(async () =>
        {
            await chat.MarkReadAsync(UserId, Flavor, conversationId, messageId, Context.ConnectionAborted);
            return true;
        });

    /// <summary>ApiException → HubException so the client sees the message instead of a generic hub error.</summary>
    private static async Task<T> Run<T>(Func<Task<T>> action)
    {
        try
        {
            return await action();
        }
        catch (ApiException e)
        {
            throw new HubException($"{e.Code}: {e.Message}");
        }
    }
}
