using System.Text.RegularExpressions;
using Servio.Api.Common;

namespace Servio.Api.Services.Requests;

/// <summary>
/// Blocks phone numbers and links in posts and quote notes (F-REQ-02, spec 0.2.7 ý 1), so customers and partners
/// cannot move the deal off the platform before an order exists. Prices like "1.500.000" are not phone numbers.
/// </summary>
public static partial class ContentGuard
{
    // 0 or +84/84 followed by 9–10 more digits, optionally separated by spaces, dots or dashes.
    [GeneratedRegex(@"(?<!\d)(?:\+?84|0)(?:[\s.\-]?\d){8,10}(?!\d)")]
    private static partial Regex PhoneNumber();

    [GeneratedRegex(@"https?://|www\.|\b[a-z0-9\-]+\.(?:com|vn|net|org|info|io|me|xyz|link|ly|co|app|site|online)\b", RegexOptions.IgnoreCase)]
    private static partial Regex Link();

    public static bool ContainsContactInfo(string? text) =>
        !string.IsNullOrEmpty(text) && (PhoneNumber().IsMatch(text) || Link().IsMatch(text));

    /// <summary>Throws CONTACT_INFO_NOT_ALLOWED (400) for <paramref name="field"/> when the text contains a phone or link.</summary>
    public static void EnsureClean(string? text, string field)
    {
        if (ContainsContactInfo(text))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ContactInfoNotAllowed,
                "Không ghi số điện thoại hoặc đường link. Hai bên trao đổi qua chat trong ứng dụng.", field);
        }
    }
}
