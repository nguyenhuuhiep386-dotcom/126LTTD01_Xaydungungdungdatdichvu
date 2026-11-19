using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services;
using Servio.Api.Services.Orders;

namespace Servio.Api.Controllers;

/// <summary>
/// #107 (course scope): the order rules the apps print (CS-18 cancellation sheet, PS-12 accept countdown), read from
/// SystemConfigs so an admin change in AW-15 shows without a new build. Fees in VND.
/// </summary>
public sealed record AppConfigDto(
    int PartnerAcceptTimeoutMinutes,
    long CancellationFeeAfterOnTheWay,
    long CancellationFeeAfterArrivedMin,
    decimal CancellationFeeAfterArrivedPercent,
    int NoShowWaitMinutes);

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
            await configs.GetIntAsync("cancellation.no_show_wait_minutes", 15, ct)));
    }
}
