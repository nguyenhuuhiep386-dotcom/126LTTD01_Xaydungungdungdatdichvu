using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Orders;

namespace Servio.Api.Controllers;

/// <summary>
/// M5 — orders (#43, #62–#66, #69, #70, #73, #74). Status changes are pushed as OrderStatusChanged on /hubs/orders to
/// both sides; each action also writes a system line in their chat. Partner endpoints act on the partner's own assignment.
/// </summary>
[Authorize]
public sealed class OrdersController(
    OrderService orders,
    AssignmentService assignments,
    ExtraChargeService extraCharges) : ApiControllerBase
{
    /// <summary>
    /// #43 Choose a quote: creates the order with one PENDING assignment; the partner has 10 minutes to accept (#65).
    /// Body (course scope): <c>{ quoteIds: [id], quoteVersions: { id: rowVersion from #42 }, paymentMethod: "CASH" }</c>.
    /// Sending the same quote again returns the same order. Errors: RESOURCE_VERSION_CONFLICT (quote changed),
    /// PARTNER_CAPACITY_EXCEEDED, SCHEDULE_CONFLICT, QUOTE_ALREADY_ACCEPTED, REQUEST_EXPIRED.
    /// </summary>
    [HttpPost("api/v1/service-requests/{id:guid}/accept-quotes")]
    [Authorize(Policy = AuthPolicies.Customer)]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<OrderDetailDto>>> AcceptQuotes(Guid id, AcceptQuotesRequest request, CancellationToken ct) =>
        CreatedEnvelope(await orders.AcceptQuoteAsync(CurrentUserId, id, request, ct));

    /// <summary>#62 My orders in this app, newest first. status: ACTIVE (includes DISPUTED), COMPLETED or CANCELLED; empty = all.</summary>
    [HttpGet("api/v1/orders/me")]
    public async Task<ActionResult<ApiResponse<PagedResult<OrderSummaryDto>>>> ListMine(
        [FromQuery] string? status, [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default) =>
        OkEnvelope(await orders.ListMineAsync(CurrentUserId, CurrentAppFlavor,
            status is null ? null : EnumText.Parse<OrderListStatus>(status, "status"), page, pageSize, ct));

    /// <summary>#63 Order detail. Phone numbers appear from ACCEPTED until the work is done.</summary>
    [HttpGet("api/v1/orders/{id:guid}")]
    public async Task<ActionResult<ApiResponse<OrderDetailDto>>> Get(Guid id, CancellationToken ct) =>
        OkEnvelope(await orders.GetAsync(CurrentUserId, CurrentAppFlavor, id, ct));

    /// <summary>#64 Status history, oldest first.</summary>
    [HttpGet("api/v1/orders/{id:guid}/timeline")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<TimelineEntryDto>>>> Timeline(Guid id, CancellationToken ct) =>
        OkEnvelope(await orders.TimelineAsync(CurrentUserId, CurrentAppFlavor, id, ct));

    /// <summary>#65 Accept the order before acceptanceDeadlineAt. Blocked by OUTSTANDING_DEBT.</summary>
    [HttpPost("api/v1/orders/{id:guid}/assignments/{aid:guid}/accept")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<OrderStatusChangedDto>>> Accept(Guid id, Guid aid, CancellationToken ct) =>
        OkEnvelope(await assignments.AcceptAsync(CurrentUserId, id, aid, ct));

    /// <summary>#66 Decline a PENDING order. The order is cancelled; the customer can post again.</summary>
    [HttpPost("api/v1/orders/{id:guid}/assignments/{aid:guid}/reject")]
    [Authorize(Policy = AuthPolicies.Partner)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Reject(Guid id, Guid aid, RejectAssignmentRequest request, CancellationToken ct)
    {
        await assignments.RejectAsync(CurrentUserId, id, aid, request, ct);
        return NoContent();
    }

    /// <summary>#69 ARRIVED → IN_PROGRESS. beforeImages: URLs from POST /files (purpose ORDER_IMAGE).</summary>
    [HttpPost("api/v1/orders/{id:guid}/assignments/{aid:guid}/start-work")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<OrderStatusChangedDto>>> StartWork(Guid id, Guid aid, StartWorkRequest request, CancellationToken ct) =>
        OkEnvelope(await assignments.StartWorkAsync(CurrentUserId, id, aid, request, ct));

    /// <summary>#70 IN_PROGRESS → COMPLETED_BY_PARTNER. Fails with EXTRA_CHARGE_PENDING while a charge waits for the customer.</summary>
    [HttpPost("api/v1/orders/{id:guid}/assignments/{aid:guid}/complete")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<OrderStatusChangedDto>>> Complete(Guid id, Guid aid, CompleteWorkRequest request, CancellationToken ct) =>
        OkEnvelope(await assignments.CompleteAsync(CurrentUserId, id, aid, request, ct));

    /// <summary>#73 Ask the customer to approve an extra charge (ARRIVED or IN_PROGRESS). The customer gets ExtraChargeRequested.</summary>
    [HttpPost("api/v1/orders/{id:guid}/extra-charges")]
    [Authorize(Policy = AuthPolicies.Partner)]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<ExtraChargeDto>>> CreateExtraCharge(Guid id, CreateExtraChargeRequest request, CancellationToken ct) =>
        CreatedEnvelope(await extraCharges.CreateAsync(CurrentUserId, id, request, ct));

    /// <summary>#74 Approve or reject an extra charge (CS-22). Approved charges are added to the total. The partner gets ExtraChargeResponded.</summary>
    [HttpPost("api/v1/orders/{id:guid}/extra-charges/{ecId:guid}/respond")]
    [Authorize(Policy = AuthPolicies.Customer)]
    public async Task<ActionResult<ApiResponse<ExtraChargeDto>>> RespondExtraCharge(Guid id, Guid ecId, RespondExtraChargeRequest request, CancellationToken ct) =>
        OkEnvelope(await extraCharges.RespondAsync(CurrentUserId, id, ecId, request, ct));
}
