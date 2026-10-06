namespace MeterReading.Api.Domain;

/// <summary>Distances on the earth's surface (spec FR-031.2: DIP office to property).</summary>
public static class Geo
{
    private const double EarthRadiusKm = 6371.0088;

    /// <summary>Great-circle distance in km (haversine). Mirrors android data/InspectionRules.distanceKm.</summary>
    public static double DistanceKm(double lat1, double lon1, double lat2, double lon2)
    {
        static double Rad(double d) => d * Math.PI / 180;
        var dLat = Rad(lat2 - lat1);
        var dLon = Rad(lon2 - lon1);
        var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) + Math.Cos(Rad(lat1)) * Math.Cos(Rad(lat2)) * Math.Sin(dLon / 2) * Math.Sin(dLon / 2);
        return 2 * EarthRadiusKm * Math.Asin(Math.Min(1, Math.Sqrt(a)));
    }
}
