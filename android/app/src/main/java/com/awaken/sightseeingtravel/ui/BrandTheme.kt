package com.awaken.sightseeingtravel.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Shared colors sampled from the Dấu Viễn Du cream-and-rust app mark. */
object BrandColors {
    val Primary = Color(0xFF622821)
    val PrimaryDeep = Color(0xFF3F1D18)
    val Terracotta = Color(0xFF9A4333)
    val Cream = Color(0xFFF6DCAD)
    val Paper = Color(0xFFFFF9F0)
    val Surface = Color(0xFFFFFCF7)
    val Sand = Color(0xFFF1E4CD)
    val Border = Color(0xFFE3D1B8)
    val Text = Color(0xFF2D211D)
    val MutedText = Color(0xFF75655C)
    val Error = Color(0xFF9A352B)
}

val SightseeingColorScheme = lightColorScheme(
    primary = BrandColors.Primary,
    onPrimary = BrandColors.Paper,
    primaryContainer = BrandColors.Cream,
    onPrimaryContainer = BrandColors.PrimaryDeep,
    secondary = BrandColors.Terracotta,
    onSecondary = BrandColors.Paper,
    secondaryContainer = BrandColors.Sand,
    onSecondaryContainer = BrandColors.PrimaryDeep,
    tertiary = BrandColors.Cream,
    onTertiary = BrandColors.PrimaryDeep,
    background = BrandColors.Paper,
    onBackground = BrandColors.Text,
    surface = BrandColors.Surface,
    onSurface = BrandColors.Text,
    surfaceVariant = BrandColors.Sand,
    onSurfaceVariant = BrandColors.MutedText,
    outline = BrandColors.Border,
    error = BrandColors.Error,
    onError = BrandColors.Paper
)
