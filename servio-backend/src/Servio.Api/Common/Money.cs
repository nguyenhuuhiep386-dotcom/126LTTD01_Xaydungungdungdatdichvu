using System.Globalization;

namespace Servio.Api.Common;

/// <summary>VND in user-facing messages: 180000 → "180.000 đ", whatever the culture of the server.</summary>
public static class Money
{
    private static readonly CultureInfo Vietnamese = CultureInfo.GetCultureInfo("vi-VN");

    public static string Vnd(decimal amount) => amount.ToString("N0", Vietnamese) + " đ";
}
