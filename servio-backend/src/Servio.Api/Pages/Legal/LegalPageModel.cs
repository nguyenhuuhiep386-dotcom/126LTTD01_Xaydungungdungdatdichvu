using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Services;
using Servio.Api.Services.Users;

namespace Servio.Api.Pages.Legal;

/// <summary>
/// Public privacy policy and terms (Luật 91/2025, Luật 122/2025). Fees, commission and deadlines are read from
/// SystemConfigs so the text always matches what the system enforces. Anonymous, no cookies.
/// </summary>
public class LegalPageModel(SystemConfigService configs, ConsentService consents) : PageModel
{
    public string Version { get; private set; } = "";
    public decimal CommissionRate { get; private set; }
    public decimal FeeOnTheWay { get; private set; }
    public decimal FeeArrivedMin { get; private set; }
    public decimal FeeArrivedPercent { get; private set; }
    public decimal MaxOutstandingFees { get; private set; }
    public decimal MaxCommissionDebt { get; private set; }
    public int AcceptMinutes { get; private set; }
    public int AutoConfirmHours { get; private set; }
    public int DisputeHours { get; private set; }
    public int UnpaidReportHours { get; private set; }
    public int ReviewDays { get; private set; }
    public int NoShowMinutes { get; private set; }
    public int PostExpireHours { get; private set; }
    public int DisputeResponseHours { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        Version = await consents.CurrentVersionAsync(ct);
        CommissionRate = await configs.GetDecimalAsync("commission.default_rate", 15, ct);
        FeeOnTheWay = await configs.GetDecimalAsync("cancellation.fee_after_on_the_way", 20_000, ct);
        FeeArrivedMin = await configs.GetDecimalAsync("cancellation.fee_after_arrived_min", 30_000, ct);
        FeeArrivedPercent = await configs.GetDecimalAsync("cancellation.fee_after_arrived_percent", 20, ct);
        MaxOutstandingFees = await configs.GetDecimalAsync("cancellation.max_outstanding_amount", 100_000, ct);
        MaxCommissionDebt = await configs.GetDecimalAsync("partner.max_commission_debt", 200_000, ct);
        AcceptMinutes = await configs.GetIntAsync("order.partner_accept_timeout_minutes", 10, ct);
        AutoConfirmHours = await configs.GetIntAsync("order.auto_confirm_hours", 24, ct);
        DisputeHours = await configs.GetIntAsync("order.dispute_window_hours", 72, ct);
        UnpaidReportHours = await configs.GetIntAsync("order.unpaid_report_window_hours", 24, ct);
        ReviewDays = await configs.GetIntAsync("review.window_days", 5, ct);
        NoShowMinutes = await configs.GetIntAsync("cancellation.no_show_wait_minutes", 15, ct);
        PostExpireHours = await configs.GetIntAsync("post.expire_hours", 24, ct);
        DisputeResponseHours = await configs.GetIntAsync(Services.Orders.DisputeService.ResponseHoursKey, 48, ct);
    }
}

public sealed class PrivacyModel(SystemConfigService configs, ConsentService consents) : LegalPageModel(configs, consents);

public sealed class TermsModel(SystemConfigService configs, ConsentService consents) : LegalPageModel(configs, consents);
