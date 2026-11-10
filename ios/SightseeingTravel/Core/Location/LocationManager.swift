import Foundation
import CoreLocation
import Combine

@MainActor
public final class LocationManager: NSObject, ObservableObject {
    public static let shared = LocationManager()

    @Published public private(set) var currentLocation: CLLocation?
    @Published public private(set) var latitude: Double = 10.7769 // Default: Ho Chi Minh City Center
    @Published public private(set) var longitude: Double = 106.7009
    @Published public private(set) var azimuthDegrees: Double = 0.0
    @Published public private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published public private(set) var locationError: String?

    private let clManager = CLLocationManager()

    public var hasFreshLocationFix: Bool {
        guard let currentLocation else { return false }
        let age = Date().timeIntervalSince(currentLocation.timestamp)
        return age >= 0 && age <= 30 && currentLocation.horizontalAccuracy >= 0 && currentLocation.horizontalAccuracy <= 50
    }

    public var horizontalAccuracyMeters: Double? {
        guard let currentLocation, currentLocation.horizontalAccuracy >= 0 else { return nil }
        return currentLocation.horizontalAccuracy
    }

    public override init() {
        super.init()
        clManager.delegate = self
        clManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        clManager.distanceFilter = 2.0
        clManager.headingFilter = 1.0
        authorizationStatus = clManager.authorizationStatus
    }

    public func requestPermissionsAndStartTracking() {
        switch clManager.authorizationStatus {
        case .notDetermined:
            clManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            startUpdating()
        case .denied, .restricted:
            locationError = "Vui lòng cấp quyền Vị trí (GPS) trong Cài đặt để khám phá các điểm du lịch và mở khóa AR."
        @unknown default:
            break
        }
    }

    public func startUpdating() {
        clManager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            clManager.startUpdatingHeading()
        }
    }

    public func requestCurrentLocation() {
        switch clManager.authorizationStatus {
        case .notDetermined:
            clManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            clManager.requestLocation()
            startUpdating()
        case .denied, .restricted:
            locationError = "Vui lòng cấp quyền Vị trí (GPS) trong Cài đặt để ghi tọa độ POI."
        @unknown default:
            break
        }
    }

    public func stopUpdating() {
        clManager.stopUpdatingLocation()
        clManager.stopUpdatingHeading()
    }

    /// Computes Haversine distance in meters between user's live GPS position and target coordinates.
    public func distanceInMeters(toLatitude targetLat: Double, longitude targetLng: Double) -> Double {
        Self.haversineDistanceMeters(
            lat1: latitude,
            lon1: longitude,
            lat2: targetLat,
            lon2: targetLng
        )
    }

    /// Evaluates whether the user is inside the POI's AR/Photo activation geofence (`activationRadiusMeters + 20m` GPS buffer).
    public func isWithinPoiGeofence(
        poiLatitude: Double,
        poiLongitude: Double,
        activationRadiusMeters: Double
    ) -> Bool {
        let distance = distanceInMeters(toLatitude: poiLatitude, longitude: poiLongitude)
        return distance <= (activationRadiusMeters + 20.0)
    }

    /// Pure Haversine formula returning distance in meters.
    public nonisolated static func haversineDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ) -> Double {
        let earthRadiusMeters = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180.0
        let dLon = (lon2 - lon1) * .pi / 180.0
        let radLat1 = lat1 * .pi / 180.0
        let radLat2 = lat2 * .pi / 180.0

        let a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                sin(dLon / 2.0) * sin(dLon / 2.0) * cos(radLat1) * cos(radLat2)
        let c = 2.0 * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
        return earthRadiusMeters * c
    }

    /// Computes initial bearing (azimuth in degrees 0..<360) from coordinate 1 to coordinate 2.
    public nonisolated static func bearingDegrees(
        fromLat lat1: Double,
        fromLon lon1: Double,
        toLat lat2: Double,
        toLon lon2: Double
    ) -> Double {
        let phi1 = lat1 * .pi / 180.0
        let phi2 = lat2 * .pi / 180.0
        let deltaLambda = (lon2 - lon1) * .pi / 180.0

        let y = sin(deltaLambda) * cos(phi2)
        let x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        let theta = atan2(y, x)
        let degrees = (theta * 180.0 / .pi).truncatingRemainder(dividingBy: 360.0)
        return degrees < 0 ? degrees + 360.0 : degrees
    }
}

extension LocationManager: CLLocationManagerDelegate {
    nonisolated public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.locationError = nil
                self.startUpdating()
            } else if status == .denied || status == .restricted {
                self.locationError = "Quyền truy cập GPS bị từ chối. Hãy bật Định vị trong Cài đặt iOS."
            }
        }
    }

    nonisolated public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latest = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = latest
            self.latitude = latest.coordinate.latitude
            self.longitude = latest.coordinate.longitude
            self.locationError = nil
        }
    }

    nonisolated public func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        Task { @MainActor in
            self.azimuthDegrees = heading
        }
    }

    nonisolated public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.locationError = error.localizedDescription
        }
    }
}
