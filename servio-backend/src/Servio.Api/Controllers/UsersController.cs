using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Users;

namespace Servio.Api.Controllers;

/// <summary>M1 — current user: profile (#9, #10), address book (#12–#15), push device (#16), consents, account deletion.</summary>
[Route("api/v1/users/me")]
[Authorize]
public sealed class UsersController(UserService users, AddressService addresses, ConsentService consents,
    AccountDeletionService deletion) : ApiControllerBase
{
    /// <summary>Current consent per purpose (TERMS_AND_PRIVACY, KYC_IDENTITY, LOCATION_SHARING).</summary>
    [HttpGet("consents")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<ConsentDto>>>> ListConsents(CancellationToken ct) =>
        OkEnvelope(await consents.ListAsync(CurrentUserId, ct));

    /// <summary>
    /// Record a consent the user just gave or withdrew on an explicit, unticked checkbox (Điều 9, 10 Luật 91/2025).
    /// policyVersion from GET /api/v1/legal. Needed before: posting (TERMS_AND_PRIVACY), KYC photos (KYC_IDENTITY),
    /// going online / moving (LOCATION_SHARING); otherwise those calls answer 422 CONSENT_REQUIRED.
    /// </summary>
    [HttpPost("consents")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<ConsentDto>>>> RecordConsent(ConsentRequest request, CancellationToken ct) =>
        OkEnvelope(await consents.RecordAsync(CurrentUserId, CurrentAppFlavor, request,
            HttpContext.Connection.RemoteIpAddress?.ToString(), Request.Headers.UserAgent.ToString(), ct));

    /// <summary>
    /// Delete the account and personal data (Điều 14 Luật 91/2025). 409 ACCOUNT_DELETION_BLOCKED with the reason while an
    /// order, open request, pending quote or debt is unfinished. Order and payment records are kept anonymised.
    /// </summary>
    [HttpDelete]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> DeleteMe(CancellationToken ct)
    {
        await deletion.DeleteAsync(CurrentUserId, CurrentAppFlavor, ct);
        return NoContent();
    }

    /// <summary>#9 Current user with roles and profiles.</summary>
    [HttpGet]
    public async Task<ActionResult<ApiResponse<MeDto>>> GetMe(CancellationToken ct) =>
        OkEnvelope(await users.GetMeAsync(CurrentUserId, ct));

    /// <summary>#10 Update name, avatar (url from POST /files, purpose AVATAR), birthday, gender or email. Null fields are left unchanged.</summary>
    [HttpPatch]
    public async Task<ActionResult<ApiResponse<MeDto>>> UpdateMe(UpdateMeRequest request, CancellationToken ct) =>
        OkEnvelope(await users.UpdateMeAsync(CurrentUserId, request, ct));

    /// <summary>#12 Address book, default first.</summary>
    [HttpGet("addresses")]
    [Authorize(Policy = AuthPolicies.Customer)]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<AddressDto>>>> ListAddresses(CancellationToken ct) =>
        OkEnvelope(await addresses.ListAsync(CurrentUserId, ct));

    /// <summary>#13 Add an address (max 10). The first address becomes the default.</summary>
    [HttpPost("addresses")]
    [Authorize(Policy = AuthPolicies.Customer)]
    public async Task<ActionResult<ApiResponse<AddressDto>>> CreateAddress(SaveAddressRequest request, CancellationToken ct) =>
        OkEnvelope(await addresses.CreateAsync(CurrentUserId, request, ct));

    /// <summary>#14 Replace an address.</summary>
    [HttpPut("addresses/{id:guid}")]
    [Authorize(Policy = AuthPolicies.Customer)]
    public async Task<ActionResult<ApiResponse<AddressDto>>> UpdateAddress(Guid id, SaveAddressRequest request, CancellationToken ct) =>
        OkEnvelope(await addresses.UpdateAsync(CurrentUserId, id, request, ct));

    /// <summary>#15 Delete an address (soft delete). If it was the default, the newest remaining address becomes default.</summary>
    [HttpDelete("addresses/{id:guid}")]
    [Authorize(Policy = AuthPolicies.Customer)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> DeleteAddress(Guid id, CancellationToken ct)
    {
        await addresses.DeleteAsync(CurrentUserId, id, ct);
        return NoContent();
    }

    /// <summary>#16 Register or refresh the FCM token of this device. appFlavor must match the token's app.</summary>
    [HttpPost("devices")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> RegisterDevice(RegisterDeviceRequest request, CancellationToken ct)
    {
        await users.RegisterDeviceAsync(CurrentUserId, CurrentAppFlavor, request, ct);
        return NoContent();
    }
}
