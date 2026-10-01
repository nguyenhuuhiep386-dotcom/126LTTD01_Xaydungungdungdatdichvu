using System.Text.RegularExpressions;

namespace Servio.Api.Common;

/// <summary>Normalizes Vietnamese mobile numbers to +84XXXXXXXXX (spec: Users.PhoneNumber).</summary>
public static partial class PhoneNumber
{
    [GeneratedRegex(@"^\+84[35789]\d{8}$")]
    private static partial Regex VietnameseMobile();

    /// <summary>Accepts 0901234567, 84901234567, +84 901 234 567. Returns null when the number is not valid.</summary>
    public static string? Normalize(string? input)
    {
        if (string.IsNullOrWhiteSpace(input))
        {
            return null;
        }

        var digits = new string(input.Where(char.IsDigit).ToArray());
        var normalized = digits switch
        {
            _ when digits.StartsWith("84") && digits.Length == 11 => "+" + digits,
            _ when digits.StartsWith('0') && digits.Length == 10 => "+84" + digits[1..],
            _ => null,
        };

        return normalized is not null && VietnameseMobile().IsMatch(normalized) ? normalized : null;
    }

    /// <summary>+84901234567 → ******4567 for display in OTP responses.</summary>
    public static string Mask(string normalized) => new string('*', normalized.Length - 4) + normalized[^4..];
}
