namespace SightseeingTravel.SharedKernel.Common;

/// <summary>
/// Provides Haversine distance calculations, geofence verification, and bounding-box helpers.
/// </summary>
public static class GeoCalculator
{
    private const double EarthRadiusMeters = 6_371_000.0;
    public const double DefaultGpsToleranceMeters = 20.0;

    /// <summary>
    /// Calculates the great-circle distance in meters between two GPS coordinates using the Haversine formula.
    /// </summary>
    public static double CalculateDistanceMeters(
        double lat1,
        double lon1,
        double lat2,
        double lon2)
    {
        var dLat = DegreesToRadians(lat2 - lat1);
        var dLon = DegreesToRadians(lon2 - lon1);

        var radLat1 = DegreesToRadians(lat1);
        var radLat2 = DegreesToRadians(lat2);

        var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) +
                Math.Cos(radLat1) * Math.Cos(radLat2) *
                Math.Sin(dLon / 2) * Math.Sin(dLon / 2);
        // Floating-point rounding can push the Haversine intermediate slightly outside [0, 1]
        // for antipodal or nearly antipodal points, making the square root below NaN.
        a = Math.Clamp(a, 0.0, 1.0);

        var c = 2 * Math.Atan2(Math.Sqrt(a), Math.Sqrt(1 - a));
        return Math.Round(EarthRadiusMeters * c, 2);
    }

    /// <summary>
    /// Verifies whether the user's current coordinate falls within the POI's activation radius plus hardware GPS tolerance.
    /// </summary>
    public static bool IsWithinGeofence(
        double userLatitude,
        double userLongitude,
        double poiLatitude,
        double poiLongitude,
        double activationRadiusMeters,
        out double actualDistanceMeters,
        double gpsToleranceMeters = DefaultGpsToleranceMeters)
    {
        actualDistanceMeters = CalculateDistanceMeters(userLatitude, userLongitude, poiLatitude, poiLongitude);
        return actualDistanceMeters <= (activationRadiusMeters + gpsToleranceMeters);
    }

    /// <summary>
    /// Computes a latitude/longitude bounding box around a center point for fast index-backed SQL filtering.
    /// </summary>
    public static (double MinLat, double MaxLat, double MinLng, double MaxLng) CalculateBoundingBox(
        double centerLat,
        double centerLng,
        double radiusKm)
    {
        var radiusMeters = Math.Max(radiusKm, 0.1) * 1000.0;
        var angularRadius = radiusMeters / EarthRadiusMeters;
        var centerLatRadians = DegreesToRadians(centerLat);
        var minLatRadians = Math.Max(centerLatRadians - angularRadius, -Math.PI / 2.0);
        var maxLatRadians = Math.Min(centerLatRadians + angularRadius, Math.PI / 2.0);

        // A search circle that reaches either pole spans every longitude. Away from the poles,
        // the spherical-cap bound avoids missing nearby points at high latitudes.
        var reachesPole = minLatRadians <= -Math.PI / 2.0 || maxLatRadians >= Math.PI / 2.0;
        if (reachesPole || angularRadius >= Math.PI)
        {
            return (
                RadiansToDegrees(minLatRadians),
                RadiansToDegrees(maxLatRadians),
                -180.0,
                180.0);
        }

        var longitudeRatio = Math.Sin(angularRadius) / Math.Cos(centerLatRadians);
        var lngDelta = RadiansToDegrees(Math.Asin(Math.Clamp(longitudeRatio, -1.0, 1.0)));
        var minLng = centerLng - lngDelta;
        var maxLng = centerLng + lngDelta;
        if (minLng < -180.0)
            minLng += 360.0;
        if (maxLng > 180.0)
            maxLng -= 360.0;

        return (
            RadiansToDegrees(minLatRadians),
            RadiansToDegrees(maxLatRadians),
            minLng,
            maxLng
        );
    }

    public static bool IsValidCoordinate(double latitude, double longitude) =>
        !double.IsNaN(latitude) && !double.IsInfinity(latitude) &&
        !double.IsNaN(longitude) && !double.IsInfinity(longitude) &&
        latitude is >= -90.0 and <= 90.0 &&
        longitude is >= -180.0 and <= 180.0;

    private static double DegreesToRadians(double degrees) => degrees * (Math.PI / 180.0);
    private static double RadiansToDegrees(double radians) => radians * (180.0 / Math.PI);
}
