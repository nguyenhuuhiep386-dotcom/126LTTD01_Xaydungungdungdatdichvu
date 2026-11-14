using System.Globalization;
using Microsoft.AspNetCore.Html;
using Servio.Api.Common;
using Servio.Api.Services.Files;

namespace Servio.Api.Pages.Admin;

/// <summary>Vietnamese labels, badges, money and time formatting shared by the admin pages.</summary>
public static class AdminText
{
    private static readonly CultureInfo Vi = CultureInfo.GetCultureInfo("vi-VN");
    private static readonly TimeSpan Vietnam = TimeSpan.FromHours(7);

    public static string Time(DateTimeOffset? at) => at is { } t ? t.ToOffset(Vietnam).ToString("dd/MM/yyyy HH:mm", Vi) : "—";

    public static string Vnd(decimal amount) => Money.Vnd(amount);

    /// <summary>App URL of a private upload (/api/v1/files/{id}) → the audited admin viewer (/admin/files/{id}).</summary>
    public static string FileUrl(string url) =>
        url.StartsWith(FileService.PrivateUrlPrefix, StringComparison.Ordinal) ? "/admin/files/" + url[FileService.PrivateUrlPrefix.Length..] : url;

    public static string Label(OrderStatus s) => s switch
    {
        OrderStatus.Pending => "Chờ đối tác xác nhận",
        OrderStatus.Accepted => "Đã xác nhận",
        OrderStatus.OnTheWay => "Đang di chuyển",
        OrderStatus.Arrived => "Đã đến nơi",
        OrderStatus.InProgress => "Đang làm",
        OrderStatus.CompletedByPartner => "Chờ khách xác nhận",
        OrderStatus.AwaitingPayment => "Chờ thanh toán",
        OrderStatus.Completed => "Hoàn tất",
        OrderStatus.Cancelled => "Đã huỷ",
        _ => "Khiếu nại",
    };

    public static string Label(ServiceRequestStatus s) => s switch
    {
        ServiceRequestStatus.Draft => "Nháp",
        ServiceRequestStatus.Open => "Đang mở",
        ServiceRequestStatus.Matched => "Đã chọn đối tác",
        ServiceRequestStatus.Expired => "Hết hạn",
        ServiceRequestStatus.Cancelled => "Khách huỷ",
        _ => "Đã ẩn",
    };

    public static string Label(UserStatus s) => s switch
    {
        UserStatus.Active => "Hoạt động",
        UserStatus.Suspended => "Đã khoá",
        UserStatus.Banned => "Cấm vĩnh viễn",
        _ => "Đã xoá",
    };

    public static string Label(DisputeStatus s) => s switch
    {
        DisputeStatus.Open => "Chờ xử lý",
        DisputeStatus.InReview => "Đang xem xét",
        DisputeStatus.Resolved => "Chấp nhận",
        _ => "Bác bỏ",
    };

    public static string Label(DisputeReason r) => r switch
    {
        DisputeReason.WorkQuality => "Chất lượng công việc",
        DisputeReason.NoShow => "Không đến",
        DisputeReason.Overcharge => "Tính tiền sai",
        DisputeReason.Damage => "Hư hỏng tài sản",
        DisputeReason.Behavior => "Thái độ",
        DisputeReason.Payment => "Thanh toán",
        DisputeReason.UnpaidCash => "Chưa trả tiền mặt",
        _ => "Khác",
    };

    public static string Label(ActorType a) => a switch
    {
        ActorType.Customer => "Khách",
        ActorType.Partner => "Đối tác",
        ActorType.Admin => "Admin",
        _ => "Hệ thống",
    };

    public static IHtmlContent Badge(OrderStatus s) => Chip(Label(s), s switch
    {
        OrderStatus.Completed => "badge-success",
        OrderStatus.Cancelled => "badge-muted",
        OrderStatus.Disputed => "badge-error",
        _ => "badge-warning",
    });

    public static IHtmlContent Badge(ServiceRequestStatus s) => Chip(Label(s), s switch
    {
        ServiceRequestStatus.Open => "badge-warning",
        ServiceRequestStatus.Matched => "badge-success",
        ServiceRequestStatus.RejectedByModeration => "badge-error",
        _ => "badge-muted",
    });

    public static IHtmlContent Badge(UserStatus s) => Chip(Label(s), s == UserStatus.Active ? "badge-success" : "badge-error");

    public static IHtmlContent Badge(DisputeStatus s) => Chip(Label(s), s switch
    {
        DisputeStatus.Open or DisputeStatus.InReview => "badge-warning",
        DisputeStatus.Resolved => "badge-success",
        _ => "badge-muted",
    });

    private static IHtmlContent Chip(string label, string css) =>
        new HtmlString($"<span class=\"badge {css}\">{System.Net.WebUtility.HtmlEncode(label)}</span>");
}
