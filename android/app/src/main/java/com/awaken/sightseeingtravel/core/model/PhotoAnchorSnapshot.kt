package com.awaken.sightseeingtravel.core.model

data class PhotoAnchorSnapshot(
    val latitude: Double,
    val longitude: Double,
    val azimuthDegrees: Float,
    val pitchDegrees: Float,
    val elevationMeters: Float = 1.5f,
    val scaleFactor: Float = 1f
)
