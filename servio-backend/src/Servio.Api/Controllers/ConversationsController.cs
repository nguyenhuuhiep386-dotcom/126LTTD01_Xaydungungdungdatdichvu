using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Chat;

namespace Servio.Api.Controllers;

/// <summary>
/// M4 — chat over HTTP (#54–#59, #61). Realtime sending and receiving go through /hubs/chat; #58 is the fallback
/// when the hub is disconnected. Each app only sees its own side: customer app = my posts, partner app = my quotes.
/// </summary>
[Route("api/v1/conversations")]
[Authorize]
public sealed class ConversationsController(ChatService chat) : ApiControllerBase
{
    /// <summary>#54 My conversations, latest message first, with unread count. status: ACTIVE or READ_ONLY.</summary>
    [HttpGet]
    public async Task<ActionResult<ApiResponse<PagedResult<ConversationDto>>>> List(
        [FromQuery] string? status, [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default) =>
        OkEnvelope(await chat.ListAsync(CurrentUserId, CurrentAppFlavor,
            status is null ? null : EnumText.Parse<ConversationStatus>(status, "status"), page, pageSize, ct));

    /// <summary>#61 Unread messages across my conversations (badge on the Messages tab).</summary>
    [HttpGet("unread-count")]
    public async Task<ActionResult<ApiResponse<UnreadCountDto>>> UnreadCount(CancellationToken ct) =>
        OkEnvelope(await chat.UnreadCountAsync(CurrentUserId, CurrentAppFlavor, ct));

    /// <summary>#55 Conversation header: the other person, the post/order and whether sending is allowed.</summary>
    [HttpGet("{id:guid}")]
    public async Task<ActionResult<ApiResponse<ConversationDto>>> Get(Guid id, CancellationToken ct) =>
        OkEnvelope(await chat.GetAsync(CurrentUserId, CurrentAppFlavor, id, ct));

    /// <summary>#56 Open (or get) the conversation of a post with a partner.</summary>
    [HttpPost]
    public async Task<ActionResult<ApiResponse<ConversationDto>>> Open(OpenConversationRequest request, CancellationToken ct) =>
        OkEnvelope(await chat.OpenAsync(CurrentUserId, CurrentAppFlavor, request, ct));

    /// <summary>
    /// #57 Messages. History: <c>?cursor=</c> (omit for the newest page), newest → oldest, continue with nextCursor.
    /// Sync after reconnect: <c>?after=syncCursor</c>, oldest → newest. limit 1–100 (default 30).
    /// </summary>
    [HttpGet("{id:guid}/messages")]
    public async Task<ActionResult<ApiResponse<MessagePage>>> Messages(
        Guid id, [FromQuery] string? cursor, [FromQuery] string? after, [FromQuery] int? limit, CancellationToken ct) =>
        OkEnvelope(await chat.GetMessagesAsync(CurrentUserId, CurrentAppFlavor, id, cursor, after, limit, ct));

    /// <summary>#58 Send a message over HTTP (fallback when /hubs/chat is down). Same rules as the hub.</summary>
    [HttpPost("{id:guid}/messages")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<MessageDto>>> Send(Guid id, SendMessageRequest request, CancellationToken ct) =>
        CreatedEnvelope(await chat.SendAsync(CurrentUserId, CurrentAppFlavor, id, request, ct));

    /// <summary>#59 Mark the other person's messages up to lastReadMessageId as read.</summary>
    [HttpPost("{id:guid}/read")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> MarkRead(Guid id, MarkReadRequest request, CancellationToken ct)
    {
        await chat.MarkReadAsync(CurrentUserId, CurrentAppFlavor, id, request.LastReadMessageId, ct);
        return NoContent();
    }
}
