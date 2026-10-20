using Servio.Api.Common;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Requests;

/// <summary>
/// Matching rules of the course scope (spec 0.2.7 ý 2, F-FEED-01). One place for the feed (#46), the NewPost
/// broadcast, the partner view of a post (#38) and quoting (#50), so they can never disagree.
/// No matchScore (F-FEED-04 is out of scope): feeds sort by distance or newest.
/// </summary>
public static class Matching
{
    public const int MaxRadiusKm = 20;
    private const double KmPerDegreeLatitude = 111.32;

    /// <summary>Coordinate box around a point, used as the cheap SQL pre-filter before the exact Haversine check.</summary>
    public static (decimal MinLat, decimal MaxLat, decimal MinLng, decimal MaxLng) Box(decimal latitude, decimal longitude, double radiusKm)
    {
        var dLat = radiusKm / KmPerDegreeLatitude;
        var dLng = radiusKm / (KmPerDegreeLatitude * Math.Max(Math.Cos((double)latitude * Math.PI / 180), 0.01));
        return ((decimal)((double)latitude - dLat), (decimal)((double)latitude + dLat),
                (decimal)((double)longitude - dLng), (decimal)((double)longitude + dLng));
    }

    /// <summary>
    /// Requirements of the post against the partner's skill for that category: years of experience of the skill,
    /// a certificate when required, and the partner rating (a partner without reviews has no rating and fails a rating requirement).
    /// </summary>
    public static bool MeetsRequirements(PartnerSkill skill, decimal? partnerRating, int? requireYears, bool requireCertificate, decimal? requireMinRating) =>
        skill.Status == (byte)ApprovalStatus.Approved
        && skill.YearsOfExperience >= (requireYears ?? 0)
        && (!requireCertificate || skill.CertificateUrl is not null)
        && (requireMinRating is null || partnerRating >= requireMinRating);

    /// <summary>Effective radius = min(partner service radius, post search radius).</summary>
    public static double RadiusKm(int partnerRadiusKm, int requestRadiusKm) => Math.Min(partnerRadiusKm, requestRadiusKm);

    /// <summary>
    /// Location used for matching: the live location while online, otherwise the anchor chosen at signup (PS-04),
    /// otherwise the last known location. Quoting additionally requires being online.
    /// </summary>
    public static (decimal Latitude, decimal Longitude)? LocationOf(PartnerProfile partner, bool isOnline) =>
        isOnline && partner.CurrentLatitude is { } lat && partner.CurrentLongitude is { } lng ? (lat, lng)
        : partner.AnchorLatitude is { } aLat && partner.AnchorLongitude is { } aLng ? (aLat, aLng)
        : partner.CurrentLatitude is { } cLat && partner.CurrentLongitude is { } cLng ? (cLat, cLng)
        : null;

    /// <summary>"12 Võ Văn Ngân, Linh Chiểu, Thủ Đức" → "Linh Chiểu, Thủ Đức": the area without the house number and street.</summary>
    public static string AreaLabel(string addressSnapshot)
    {
        var parts = addressSnapshot.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        return parts.Length switch
        {
            0 => "",
            1 or 2 => parts[^1],
            _ => $"{parts[^2]}, {parts[^1]}",
        };
    }

    public static double RoundKm(double km) => Math.Round(km, 1);
}
