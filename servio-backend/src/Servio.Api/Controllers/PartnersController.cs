using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Partners;

namespace Servio.Api.Controllers;

/// <summary>M1 — partner profile, KYC, skills, online status (#17–#23, #25–#28).</summary>
[Route("api/v1/partners")]
[Authorize]
public sealed class PartnersController(
    PartnerProfileService profiles,
    PartnerSkillService skills,
    PartnerPublicService publicProfiles) : ApiControllerBase
{
    /// <summary>#17 Own partner profile, KYC documents, skills and what is still missing before submitting.</summary>
    [HttpGet("me")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<PartnerMeDto>>> GetMe(CancellationToken ct) =>
        OkEnvelope(await profiles.GetMeAsync(CurrentUserId, ct));

    /// <summary>#18 Update bio, years of experience, service radius (3/5/10/20 km) or anchor location.</summary>
    [HttpPatch("me")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<PartnerMeDto>>> UpdateMe(UpdatePartnerRequest request, CancellationToken ct) =>
        OkEnvelope(await profiles.UpdateAsync(CurrentUserId, request, ct));

    /// <summary>#19 Attach a KYC document (fileUrl from POST /files with purpose KYC). Replaces a pending/rejected one of the same type.</summary>
    [HttpPost("me/documents")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<PartnerDocumentDto>>> AddDocument(AddDocumentRequest request, CancellationToken ct) =>
        OkEnvelope(await profiles.AddDocumentAsync(CurrentUserId, request, ct));

    /// <summary>#20 Submit the profile for admin review (NOT_SUBMITTED/REJECTED → PENDING).</summary>
    [HttpPost("me/submit-verification")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<VerificationStatusDto>>> Submit(CancellationToken ct) =>
        OkEnvelope(await profiles.SubmitAsync(CurrentUserId, ct));

    /// <summary>#21 Registered skills with approval status.</summary>
    [HttpGet("me/skills")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<PartnerSkillDto>>>> ListSkills(CancellationToken ct) =>
        OkEnvelope(await skills.ListAsync(CurrentUserId, ct));

    /// <summary>#22 Add a skill (level-2 category, max 5). New skills start PENDING.</summary>
    [HttpPost("me/skills")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<PartnerSkillDto>>> AddSkill(AddSkillRequest request, CancellationToken ct) =>
        OkEnvelope(await skills.AddAsync(CurrentUserId, request, ct));

    /// <summary>#23 Remove a skill.</summary>
    [HttpDelete("me/skills/{id:guid}")]
    [Authorize(Policy = AuthPolicies.Partner)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> DeleteSkill(Guid id, CancellationToken ct)
    {
        await skills.DeleteAsync(CurrentUserId, id, ct);
        return NoContent();
    }

    /// <summary>#25 Go online (needs APPROVED + location) or offline.</summary>
    [HttpPost("me/online-status")]
    [Authorize(Policy = AuthPolicies.Partner)]
    public async Task<ActionResult<ApiResponse<OnlineStatusDto>>> SetOnline(OnlineStatusRequest request, CancellationToken ct) =>
        OkEnvelope(await profiles.SetOnlineAsync(CurrentUserId, request, ct));

    /// <summary>#26 Heartbeat with the current location while online (every partner.heartbeat_seconds = 30 s).</summary>
    [HttpPost("me/heartbeat")]
    [Authorize(Policy = AuthPolicies.Partner)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Heartbeat(HeartbeatRequest request, CancellationToken ct)
    {
        await profiles.HeartbeatAsync(CurrentUserId, request, ct);
        return NoContent();
    }

    /// <summary>#27 Public profile (CS-17). Only for users related through a quote, chat or order.</summary>
    [HttpGet("{id:guid}")]
    public async Task<ActionResult<ApiResponse<PartnerPublicDto>>> GetPublic(Guid id, CancellationToken ct) =>
        OkEnvelope(await publicProfiles.GetAsync(CurrentUserId, id, ct));

    /// <summary>#28 Published customer reviews of a partner, newest first.</summary>
    [HttpGet("{id:guid}/reviews")]
    public async Task<ActionResult<ApiResponse<PagedResult<PartnerReviewDto>>>> GetReviews(
        Guid id, [FromQuery, Range(1, int.MaxValue)] int page = 1, [FromQuery, Range(1, 50)] int pageSize = 10, CancellationToken ct = default) =>
        OkEnvelope(await publicProfiles.GetReviewsAsync(CurrentUserId, id, page, pageSize, ct));
}
