using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Requests;

namespace Servio.Api.Controllers;

/// <summary>M2 — service requests (#36–#38, #40) and their quotes (#42 customer, #50 partner).</summary>
[Route("api/v1/service-requests")]
[Authorize]
public sealed class ServiceRequestsController(
    ServiceRequestService requests,
    FeedService feed,
    QuoteService quotes) : ApiControllerBase
{
    /// <summary>#36 Post a request. Goes OPEN immediately and is pushed to matching online partners (NewPost on /hubs/feed).</summary>
    [HttpPost]
    [Authorize(Policy = AuthPolicies.Customer)]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<RequestDetailDto>>> Create(CreateServiceRequestRequest request, CancellationToken ct) =>
        CreatedEnvelope(await requests.CreateAsync(CurrentUserId, request, ct));

    /// <summary>#37 My requests, newest first. status: comma-separated, e.g. <c>OPEN</c> or <c>EXPIRED,CANCELLED</c>; empty = all.</summary>
    [HttpGet("me")]
    [Authorize(Policy = AuthPolicies.Customer)]
    public async Task<ActionResult<ApiResponse<PagedResult<RequestSummaryDto>>>> ListMine(
        [FromQuery] string? status, [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default)
    {
        var statuses = (status ?? "").Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Select(s => EnumText.Parse<ServiceRequestStatus>(s, "status"))
            .ToList();
        return OkEnvelope(await requests.ListMineAsync(CurrentUserId, statuses, page, pageSize, ct));
    }

    /// <summary>
    /// #38 Request detail. Customer app (owner): <see cref="RequestDetailDto"/> with the exact address.
    /// Partner app: <see cref="PartnerRequestViewDto"/> with area + distance only, own quote and canQuote.
    /// </summary>
    [HttpGet("{id:guid}")]
    [ProducesResponseType<ApiResponse<RequestDetailDto>>(StatusCodes.Status200OK)]
    [ProducesResponseType<ApiResponse<PartnerRequestViewDto>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<ApiResponse<object>>> Get(Guid id, CancellationToken ct) =>
        CurrentAppFlavor == AppFlavor.Customer
            ? OkEnvelope<object>(await requests.GetForOwnerAsync(CurrentUserId, id, ct))
            : OkEnvelope<object>(await feed.GetForPartnerAsync(CurrentUserId, id, ct));

    /// <summary>#40 Cancel an OPEN request. Pending quotes expire.</summary>
    [HttpPost("{id:guid}/cancel")]
    [Authorize(Policy = AuthPolicies.Customer)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Cancel(Guid id, CancelRequestRequest request, CancellationToken ct)
    {
        await requests.CancelAsync(CurrentUserId, id, request.Reason, ct);
        return NoContent();
    }

    /// <summary>#42 Quotes on my request (current revision). sort: TIME (default, earliest available), PRICE, RATING.</summary>
    [HttpGet("{id:guid}/quotes")]
    [Authorize(Policy = AuthPolicies.Customer)]
    public async Task<ActionResult<ApiResponse<PagedResult<CustomerQuoteDto>>>> ListQuotes(
        Guid id, [FromQuery] string? sort, [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default) =>
        OkEnvelope(await quotes.ListForCustomerAsync(CurrentUserId, id,
            sort is null ? QuoteSort.Time : EnumText.Parse<QuoteSort>(sort, "sort"), page, pageSize, ct));

    /// <summary>#50 Send a quote (partner online, eligible, max 5 pending). Creates the chat conversation; the customer gets NewQuote.</summary>
    [HttpPost("{id:guid}/quotes")]
    [Authorize(Policy = AuthPolicies.Partner)]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<PartnerQuoteDto>>> SubmitQuote(Guid id, SubmitQuoteRequest request, CancellationToken ct) =>
        CreatedEnvelope(await quotes.SubmitAsync(CurrentUserId, id, request, ct));
}
