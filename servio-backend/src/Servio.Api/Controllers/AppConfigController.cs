using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Requests;

namespace Servio.Api.Controllers;

/// <summary>
/// #107 (course scope): the rules and contacts the apps print (CS-18 cancellation sheet, PS-12 accept countdown, CS-30
/// bank transfer, CS-34 support, CS-06 posting form), read from SystemConfigs so an admin change in AW-15 shows without a
/// new build. Fees in VND. A contact the admin has not filled in yet is null: hide it.
/// </summary>
public sealed record AppConfigDto(
    int PartnerAcceptTimeoutMinutes,
    long CancellationFeeAfterOnTheWay,
    long CancellationFeeAfterArrivedMin,
    decimal CancellationFeeAfterArrivedPercent,
    int NoShowWaitMinutes,
    int AutoConfirmHours,
    int DisputeWindowHours,
    int DisputeResponseHours,
    int ReviewWindowDays,
    PostingRulesDto Posting,
    BankAccountDto CancellationFeeBank,
    SupportDto Support);

/// <summary>NOW = a slot of windowHours from now; SCHEDULED = start between minLeadMinutes and maxLeadDays ahead.</summary>
public sealed record PostingRulesDto(int WindowHours, int MinLeadMinutes, int MaxLeadDays, int ExpireHours);

/// <summary>Where customers transfer cancellation fees (#156). transferNoteTemplate: the app replaces {phone}.</summary>
public sealed record BankAccountDto(string? BankName, string? AccountNumber, string? AccountHolder, string TransferNoteTemplate);

public sealed record SupportDto(string? Hotline, string? Hours);

[AllowAnonymous]
[Route("api/v1/app-config")]
public sealed class AppConfigController(SystemConfigService configs, CancellationService cancellation) : ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<ApiResponse<AppConfigDto>>> Get(CancellationToken ct)
    {
        var fees = await cancellation.PolicyAsync(ct);
        return OkEnvelope(new AppConfigDto(
            await configs.GetIntAsync("order.partner_accept_timeout_minutes", 10, ct),
            fees.OnTheWay, fees.ArrivedMin, fees.ArrivedPercent,
            await configs.GetIntAsync("cancellation.no_show_wait_minutes", 15, ct),
            await configs.GetIntAsync("order.auto_confirm_hours", 24, ct),
            await configs.GetIntAsync("order.dispute_window_hours", 72, ct),
            await configs.GetIntAsync(DisputeService.ResponseHoursKey, 48, ct),
            await configs.GetIntAsync("review.window_days", 5, ct),
            new PostingRulesDto(
                (int)ServiceRequestService.Window.TotalHours,
                (int)ServiceRequestService.MinLeadTime.TotalMinutes,
                (int)ServiceRequestService.MaxLeadTime.TotalDays,
                await configs.GetIntAsync("post.expire_hours", 24, ct)),
            new BankAccountDto(
                await TextAsync("payment.bank_name", ct),
                await TextAsync("payment.bank_account_number", ct),
                await TextAsync("payment.bank_account_holder", ct),
                await configs.GetStringAsync("payment.transfer_note_template", "SERVIO {phone}", ct)),
            new SupportDto(await TextAsync("support.hotline", ct), await TextAsync("support.hours", ct))));
    }

    private async Task<string?> TextAsync(string key, CancellationToken ct) =>
        await configs.GetStringAsync(key, "", ct) is { Length: > 0 } value ? value : null;
}
