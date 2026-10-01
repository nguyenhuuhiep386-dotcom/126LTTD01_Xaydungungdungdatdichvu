using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Servio.Api.Common;

/// <summary>"Vệ sinh máy lạnh" → "ve-sinh-may-lanh" (ServiceCategories.Slug).</summary>
public static partial class Slug
{
    [GeneratedRegex("[^a-z0-9]+")]
    private static partial Regex NonAlphanumeric();

    [GeneratedRegex("^[a-z0-9]+(-[a-z0-9]+)*$")]
    private static partial Regex ValidSlug();

    public static string From(string text)
    {
        var decomposed = text.Trim().ToLowerInvariant().Replace('đ', 'd').Normalize(NormalizationForm.FormD);
        var ascii = new string(decomposed.Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark).ToArray());
        return NonAlphanumeric().Replace(ascii, "-").Trim('-');
    }

    public static bool IsValid(string slug) => slug.Length <= 150 && ValidSlug().IsMatch(slug);
}
