namespace Servio.Api.Common;

/// <summary>Coordinate helpers (spec 0 ý 7, course scope 0.2.2 row 3: Haversine in C#, no geography column).</summary>
public static class Geo
{
    private const double EarthRadiusKm = 6371.0;

    public static bool IsValid(decimal? latitude, decimal? longitude) =>
        latitude is >= -90 and <= 90 && longitude is >= -180 and <= 180;

    /// <summary>Throws 400 unless both coordinates are present and in range. Field names follow the request body.</summary>
    public static (decimal Latitude, decimal Longitude) Require(decimal? latitude, decimal? longitude, string field = "latitude")
    {
        if (!IsValid(latitude, longitude))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Vị trí không hợp lệ", field);
        }
        return (Math.Round(latitude!.Value, 6), Math.Round(longitude!.Value, 6));
    }

    public static double DistanceKm(decimal lat1, decimal lng1, decimal lat2, decimal lng2)
    {
        static double Rad(decimal degrees) => (double)degrees * Math.PI / 180;
        var dLat = Rad(lat2 - lat1);
        var dLng = Rad(lng2 - lng1);
        var a = Math.Pow(Math.Sin(dLat / 2), 2) + Math.Cos(Rad(lat1)) * Math.Cos(Rad(lat2)) * Math.Pow(Math.Sin(dLng / 2), 2);
        return 2 * EarthRadiusKm * Math.Asin(Math.Sqrt(a));
    }
}
