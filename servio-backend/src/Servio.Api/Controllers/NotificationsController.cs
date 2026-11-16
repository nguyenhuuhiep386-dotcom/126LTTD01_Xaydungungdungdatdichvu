using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Notifications;

namespace Servio.Api.Controllers;

/// <summary>M9 — in-app notification center (#101–#104, CS-31 / PS-28) for the app of the token.</summary>
[Authorize]
[Route("api/v1/notifications")]
public sealed class NotificationsController(NotificationService notifications) : ApiControllerBase
{
    /// <summary>#101 Newest first. isRead filters read/unread; empty = all.</summary>
    [HttpGet]
    public async Task<ActionResult<ApiResponse<PagedResult<NotificationDto>>>> List(
        [FromQuery] bool? isRead, [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default) =>
        OkEnvelope(await notifications.ListAsync(CurrentUserId, CurrentAppFlavor, isRead, page, pageSize, ct));

    /// <summary>#102</summary>
    [HttpPost("{id:guid}/read")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Read(Guid id, CancellationToken ct)
    {
        await notifications.MarkReadAsync(CurrentUserId, CurrentAppFlavor, id, ct);
        return NoContent();
    }

    /// <summary>#103</summary>
    [HttpPost("read-all")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> ReadAll(CancellationToken ct)
    {
        await notifications.MarkAllReadAsync(CurrentUserId, CurrentAppFlavor, ct);
        return NoContent();
    }

    /// <summary>#104 Badge: { total, byType: { NEW_QUOTE: 2, ... } }.</summary>
    [HttpGet("unread-count")]
    public async Task<ActionResult<ApiResponse<UnreadCountDto>>> UnreadCount(CancellationToken ct) =>
        OkEnvelope(await notifications.UnreadCountAsync(CurrentUserId, CurrentAppFlavor, ct));
}
