package com.awaken.sightseeingtravel.core.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
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
    val locationFixElapsedRealtimeNanos: Long = 0L,
    val isSimulatedLocation: Boolean = false,
    val speedMetersPerSecond: Float = 0f,
    val azimuthDegrees: Float = 0f, // 0..360 (0=N, 90=E, 180=S, 270=W)
    val courseDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,   // Up/Down camera tilt (-90..+90)
    val rollDegrees: Float = 0f
) {
    val locationFixAgeMillis: Long
        get() {
            if (!hasLiveGpsFix) return Long.MAX_VALUE
            if (locationFixElapsedRealtimeNanos > 0L) {
                val ageNanos = SystemClock.elapsedRealtimeNanos() - locationFixElapsedRealtimeNanos
                return if (ageNanos >= 0L) ageNanos / 1_000_000L else Long.MAX_VALUE
            }
            val wallClockAge = System.currentTimeMillis() - locationFixTimeMillis
            return if (wallClockAge >= 0L) wallClockAge else Long.MAX_VALUE
        }

    val hasFreshGpsFix: Boolean
        get() = hasLiveGpsFix && locationFixAgeMillis <= 30_000L

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

    private val mainHandler = Handler(Looper.getMainLooper())

    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var isLocationTracking = false
    private var isLocationRequestPending = false
    private var shouldTrackLocation = false
    private var locationRequestGeneration = 0
    private var locationRetryAttempt = 0
    private var locationRetryRunnable: Runnable? = null
    private var currentLocationToken: CancellationTokenSource? = null
    private var isSensorTracking = false

    private val locationRecoveryRunnable = object : Runnable {
        override fun run() {
            if (!shouldTrackLocation) return
            if (!_spatialPose.value.hasFreshGpsFix) {
                requestCurrentLocation()
            }
            mainHandler.postDelayed(this, LOCATION_RECOVERY_INTERVAL_MILLIS)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.locations.maxByOrNull { it.elapsedRealtimeNanos }
                ?: result.lastLocation
                ?: return
            publishLocation(loc)
            locationRetryAttempt = 0
        }

    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        shouldTrackLocation = true
        if (!hasLocationPermission()) return

        if (!isLocationTracking && !isLocationRequestPending) {
            requestLocationUpdates()
        }
        mainHandler.removeCallbacks(locationRecoveryRunnable)
        mainHandler.postDelayed(locationRecoveryRunnable, LOCATION_RECOVERY_INTERVAL_MILLIS)
        if (!_spatialPose.value.hasFreshGpsFix) {
            requestCurrentLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (!shouldTrackLocation || isLocationTracking || isLocationRequestPending) return
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            // A distance threshold can suppress updates while the user is stationary,
            // causing the live fix to age out even though location is still enabled.
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()
        val generation = ++locationRequestGeneration
        isLocationRequestPending = true

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                if (generation != locationRequestGeneration || !shouldTrackLocation) {
                    if (!shouldTrackLocation) fusedLocationClient.removeLocationUpdates(locationCallback)
                    return@addOnSuccessListener
                }
                isLocationRequestPending = false
                isLocationTracking = true
                locationRetryAttempt = 0
            }.addOnFailureListener {
                if (generation != locationRequestGeneration) return@addOnFailureListener
                isLocationRequestPending = false
                isLocationTracking = false
                scheduleLocationRetry()
            }.addOnCanceledListener {
                if (generation != locationRequestGeneration) return@addOnCanceledListener
                isLocationRequestPending = false
                isLocationTracking = false
                scheduleLocationRetry()
            }
        } catch (_: SecurityException) {
            isLocationRequestPending = false
            isLocationTracking = false
        }
    }

    private fun scheduleLocationRetry() {
        if (!shouldTrackLocation) return
        locationRetryRunnable?.let(mainHandler::removeCallbacks)
        val delayMillis = (1_000L shl locationRetryAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        locationRetryAttempt = (locationRetryAttempt + 1).coerceAtMost(5)
        val retry = Runnable {
            locationRetryRunnable = null
            requestLocationUpdates()
        }
        locationRetryRunnable = retry
        mainHandler.postDelayed(retry, delayMillis)
    }

    private fun hasLocationPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun requestCurrentLocation(onResult: (DeviceSpatialPose?) -> Unit = {}) {
        if (!hasLocationPermission()) {
            onResult(null)
            return
        }

        currentLocationToken?.cancel()
        val tokenSource = CancellationTokenSource()
        currentLocationToken = tokenSource
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MILLIS)
            .setMaxUpdateAgeMillis(0L)
            .build()

        try {
            fusedLocationClient.getCurrentLocation(request, tokenSource.token)
                .addOnSuccessListener { location ->
                    if (currentLocationToken !== tokenSource) return@addOnSuccessListener
                    currentLocationToken = null
                    val pose = location?.let(::publishLocation)
                    onResult(pose)
                }
                .addOnFailureListener {
                    if (currentLocationToken !== tokenSource) return@addOnFailureListener
                    currentLocationToken = null
                    onResult(null)
                }
                .addOnCanceledListener {
                    if (currentLocationToken !== tokenSource) return@addOnCanceledListener
                    currentLocationToken = null
                    onResult(null)
                }
        } catch (_: SecurityException) {
            if (currentLocationToken === tokenSource) currentLocationToken = null
            onResult(null)
        }
    }

    private fun publishLocation(loc: Location): DeviceSpatialPose {
        val previous = _spatialPose.value
        val fixElapsedRealtimeNanos = if (loc.elapsedRealtimeNanos > 0L) {
            loc.elapsedRealtimeNanos
        } else {
            val wallClockAgeMillis = (System.currentTimeMillis() - loc.time).coerceAtLeast(0L)
            (SystemClock.elapsedRealtimeNanos() - wallClockAgeMillis * 1_000_000L).coerceAtLeast(0L)
        }
        if (previous.locationFixElapsedRealtimeNanos > fixElapsedRealtimeNanos &&
            previous.hasLiveGpsFix &&
            !previous.isSimulatedLocation
        ) {
            return previous
        }

        val displacementMeters = if (previous.hasFreshGpsFix) {
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
        return previous.copy(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitudeMeters = if (loc.hasAltitude()) loc.altitude else 5.0,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10.0f,
            hasLiveGpsFix = true,
            locationFixTimeMillis = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            locationFixElapsedRealtimeNanos = fixElapsedRealtimeNanos,
            isSimulatedLocation = false,
            speedMetersPerSecond = if (loc.hasSpeed()) loc.speed else 0f,
            courseDegrees = gpsCourse?.let {
                smoothAngleDegrees(previous.courseDegrees, it, alpha = 0.35f)
            } ?: previous.courseDegrees
        ).also { _spatialPose.value = it }
    }

    fun stopLocationUpdates() {
        shouldTrackLocation = false
        locationRequestGeneration++
        isLocationRequestPending = false
        locationRetryAttempt = 0
        locationRetryRunnable?.let(mainHandler::removeCallbacks)
        locationRetryRunnable = null
        mainHandler.removeCallbacks(locationRecoveryRunnable)
        currentLocationToken?.cancel()
        currentLocationToken = null
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
            locationFixElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
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
        private const val LOCATION_RECOVERY_INTERVAL_MILLIS = 20_000L
        private const val CURRENT_LOCATION_TIMEOUT_MILLIS = 15_000L

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
