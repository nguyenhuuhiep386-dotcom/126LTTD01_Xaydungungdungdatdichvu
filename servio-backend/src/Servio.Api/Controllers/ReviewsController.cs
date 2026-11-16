using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Reviews;

namespace Servio.Api.Controllers;

/// <summary>M7 reviews (#95, #96, #100) and disputes (#146–#148) for the app of the token.</summary>
[Authorize]
public sealed class ReviewsController(ReviewService reviews, DisputeService disputes) : ApiControllerBase
{
    /// <summary>
    /// #95 Review the other side of a COMPLETED assignment within 5 days (CS-26 / PS-27). The customer may add the 4
    /// criteria; the partner uses tags. 409 REVIEW_ALREADY_SUBMITTED, 422 REVIEW_WINDOW_CLOSED.
    /// </summary>
    [HttpPost("api/v1/orders/{id:guid}/reviews")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<ReviewDto>>> Create(Guid id, CreateReviewRequest request, CancellationToken ct) =>
        CreatedEnvelope(await reviews.CreateAsync(CurrentUserId, CurrentAppFlavor, id, request, ct));

    /// <summary>#96 Reviews of the order (published immediately in the course scope).</summary>
    [HttpGet("api/v1/orders/{id:guid}/reviews")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<ReviewDto>>>> ForOrder(Guid id, CancellationToken ct) =>
        OkEnvelope(await reviews.ListForOrderAsync(CurrentUserId, CurrentAppFlavor, id, ct));

    /// <summary>#100 Orders waiting for your review, with the deadline.</summary>
    [HttpGet("api/v1/reviews/pending")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<PendingReviewDto>>>> Pending(CancellationToken ct) =>
        OkEnvelope(await reviews.PendingAsync(CurrentUserId, CurrentAppFlavor, ct));

    /// <summary>
    /// #146 Open a dispute (CS-33): during the work or ≤ 72 h after completion. The order becomes DISPUTED and every action
    /// stops until the admin decides (AW-08). 409/422 DISPUTE_NOT_ALLOWED says why.
    /// </summary>
    [HttpPost("api/v1/orders/{id:guid}/disputes")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<DisputeDto>>> OpenDispute(Guid id, CreateDisputeRequest request, CancellationToken ct) =>
        CreatedEnvelope(await disputes.CreateAsync(CurrentUserId, CurrentAppFlavor, id, request, ct));

    /// <summary>#147 Disputes of the order.</summary>
    [HttpGet("api/v1/orders/{id:guid}/disputes")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<DisputeDto>>>> OrderDisputes(Guid id, CancellationToken ct) =>
        OkEnvelope(await disputes.ListForOrderAsync(CurrentUserId, CurrentAppFlavor, id, ct));

    /// <summary>#148 One dispute with its status and the admin's decision.</summary>
    [HttpGet("api/v1/disputes/{id:guid}")]
    public async Task<ActionResult<ApiResponse<DisputeDto>>> Dispute(Guid id, CancellationToken ct) =>
        OkEnvelope(await disputes.GetAsync(CurrentUserId, CurrentAppFlavor, id, ct));
}
