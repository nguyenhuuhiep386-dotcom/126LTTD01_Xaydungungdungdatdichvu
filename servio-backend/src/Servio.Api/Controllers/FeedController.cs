using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Quotes;

namespace Servio.Api.Controllers;

/// <summary>M3 — partner newsfeed (#46) and own quotes (#52, #53).</summary>
[Authorize(Policy = AuthPolicies.Partner)]
public sealed class FeedController(FeedService feed, QuoteService quotes) : ApiControllerBase
{
    /// <summary>
    /// #46 Open posts that match my approved skills and radius. sort: DISTANCE (default) or NEWEST.
    /// categoryIds: repeat the parameter to filter (PS-08). Live updates arrive as NewPost on /hubs/feed.
    /// </summary>
    [HttpGet("api/v1/feed")]
    public async Task<ActionResult<ApiResponse<PagedResult<FeedItemDto>>>> GetFeed(
        [FromQuery] Guid[]? categoryIds,
        [FromQuery] double? maxDistanceKm,
        [FromQuery] string? sort,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20,
        CancellationToken ct = default) =>
        OkEnvelope(await feed.GetFeedAsync(CurrentUserId, categoryIds, maxDistanceKm,
            sort is null ? FeedSort.Distance : EnumText.Parse<FeedSort>(sort, "sort"), page, pageSize, ct));

    /// <summary>#53 My quotes, newest first. status: PENDING, ACCEPTED, REJECTED, WITHDRAWN or EXPIRED (PS-12 tabs).</summary>
    [HttpGet("api/v1/quotes/me")]
    public async Task<ActionResult<ApiResponse<PagedResult<PartnerQuoteDto>>>> ListMyQuotes(
        [FromQuery] string? status, [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default) =>
        OkEnvelope(await quotes.ListMineAsync(CurrentUserId,
            status is null ? null : EnumText.Parse<QuoteStatus>(status, "status"), page, pageSize, ct));

    /// <summary>#52 Withdraw a PENDING quote. The customer gets QuoteWithdrawn on /hubs/orders.</summary>
    [HttpPost("api/v1/quotes/{id:guid}/withdraw")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Withdraw(Guid id, WithdrawQuoteRequest request, CancellationToken ct)
    {
        await quotes.WithdrawAsync(CurrentUserId, id, ct);
        return NoContent();
    }
}
