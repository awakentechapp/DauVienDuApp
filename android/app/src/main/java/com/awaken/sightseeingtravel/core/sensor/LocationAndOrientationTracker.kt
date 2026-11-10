package com.awaken.sightseeingtravel.core.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Live GPS Location + 3D Device Orientation State (`azimuthDegrees`, `pitchDegrees`, `rollDegrees`)
 */
data class DeviceSpatialPose(
    val latitude: Double = DEFAULT_HO_CHI_MINH_LAT,
    val longitude: Double = DEFAULT_HO_CHI_MINH_LNG,
    val altitudeMeters: Double = 5.0,
    val accuracyMeters: Float = 12.0f,
    val hasLiveGpsFix: Boolean = false,
    val locationFixTimeMillis: Long = 0L,
    val isSimulatedLocation: Boolean = false,
    val speedMetersPerSecond: Float = 0f,
    val azimuthDegrees: Float = 0f, // 0..360 (0=N, 90=E, 180=S, 270=W)
    val courseDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,   // Up/Down camera tilt (-90..+90)
    val rollDegrees: Float = 0f
) {
    val hasFreshGpsFix: Boolean
        get() {
            val ageMillis = System.currentTimeMillis() - locationFixTimeMillis
            return hasLiveGpsFix && ageMillis in 0L..30_000L
        }

    companion object {
        // Default initial coordinates: Notre-Dame Cathedral / Central Post Office Saigon
        const val DEFAULT_HO_CHI_MINH_LAT = 10.779785
        const val DEFAULT_HO_CHI_MINH_LNG = 106.699018
    }
}

/**
 * Tracks real-time GPS (`FusedLocationProviderClient`) and 3D Compass Orientation
 * (`Sensor.TYPE_ROTATION_VECTOR`) via Kotlin `StateFlow` for MapLibre & Spatial AR View.
 */
class LocationAndOrientationTracker(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val sensorManager: SensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _spatialPose = MutableStateFlow(DeviceSpatialPose())
    val spatialPose: StateFlow<DeviceSpatialPose> = _spatialPose.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var isLocationTracking = false
    private var isSensorTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            val previous = _spatialPose.value
            val displacementMeters = if (previous.hasLiveGpsFix) {
                calculateDistanceMeters(previous.latitude, previous.longitude, loc.latitude, loc.longitude)
            } else {
                0.0
            }
            val gpsCourse = when {
                loc.hasBearing() && loc.hasSpeed() && loc.speed >= 0.8f -> loc.bearing
                displacementMeters >= 3.0 -> calculateBearingDegrees(
                    previous.latitude,
                    previous.longitude,
                    loc.latitude,
                    loc.longitude
                )
                else -> null
            }
            _spatialPose.value = previous.copy(
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeMeters = if (loc.hasAltitude()) loc.altitude else 5.0,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10.0f,
                hasLiveGpsFix = true,
                locationFixTimeMillis = loc.time,
                isSimulatedLocation = false,
                speedMetersPerSecond = if (loc.hasSpeed()) loc.speed else 0f,
                courseDegrees = gpsCourse?.let {
                    smoothAngleDegrees(previous.courseDegrees, it, alpha = 0.35f)
                } ?: previous.courseDegrees
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isLocationTracking) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .setMinUpdateDistanceMeters(1.5f)
                .build()
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            isLocationTracking = true
        } catch (_: SecurityException) {
            isLocationTracking = false
        }
    }

    fun stopLocationUpdates() {
        if (!isLocationTracking) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isLocationTracking = false
    }

    fun startOrientationUpdates() {
        if (isSensorTracking) return
        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            isSensorTracking = true
        }
    }

    fun stopOrientationUpdates() {
        if (!isSensorTracking) return
        sensorManager.unregisterListener(this)
        isSensorTracking = false
    }

    /**
     * Allows manual simulation of GPS / Compass orientation during indoor testing or demo mode.
     */
    fun simulateLocationAndHeading(latitude: Double, longitude: Double, azimuthDegrees: Float? = null) {
        _spatialPose.value = _spatialPose.value.copy(
            latitude = latitude,
            longitude = longitude,
            hasLiveGpsFix = true,
            locationFixTimeMillis = System.currentTimeMillis(),
            isSimulatedLocation = true,
            azimuthDegrees = azimuthDegrees ?: _spatialPose.value.azimuthDegrees
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // Remap coordinate system for upright portrait camera holding posture (X -> X, Z -> Y)
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedRotationMatrix
        )
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        val rawAzimuthDeg = (Math.toDegrees(orientationAngles[0].toDouble()).toFloat() + 360f) % 360f
        val rawPitchDeg = -Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rawRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        // Low-pass filter for smooth AR overlay rendering without jitter
        val prev = _spatialPose.value
        val smoothedAzimuth = smoothAngleDegrees(prev.azimuthDegrees, rawAzimuthDeg, alpha = 0.18f)
        val smoothedPitch = prev.pitchDegrees + 0.18f * (rawPitchDeg - prev.pitchDegrees)

        _spatialPose.value = prev.copy(
            azimuthDegrees = smoothedAzimuth,
            courseDegrees = if (prev.speedMetersPerSecond < 0.8f) smoothedAzimuth else prev.courseDegrees,
            pitchDegrees = smoothedPitch,
            rollDegrees = rawRollDeg
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /**
         * Circular interpolation for compass angles across the 0 / 360 degree boundary.
         */
        fun smoothAngleDegrees(current: Float, target: Float, alpha: Float): Float {
            var delta = ((target - current + 540f) % 360f) - 180f
            return (current + alpha * delta + 360f) % 360f
        }

        /**
         * Signed shortest angular difference `(targetAzimuth - viewerAzimuth)` in `[-180, +180]`.
         */
        fun signedAngleDeltaDegrees(viewerAzimuth: Float, targetAzimuth: Float): Float {
            return ((targetAzimuth - viewerAzimuth + 540f) % 360f) - 180f
        }

        /**
         * Haversine distance in meters between two WGS84 coordinates.
         */
        fun calculateDistanceMeters(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double
        ): Double {
            val earthRadiusMeters = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2.0)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadiusMeters * c
        }

        /**
         * Bearing in degrees (0..360) from `(lat1, lng1)` to `(lat2, lng2)`.
         */
        fun calculateBearingDegrees(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double
        ): Float {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaLambda = Math.toRadians(lng2 - lng1)
            val y = sin(deltaLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
            val theta = atan2(y, x)
            return ((Math.toDegrees(theta).toFloat() + 360f) % 360f)
        }
    }
}
