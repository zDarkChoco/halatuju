package com.mbk.halatuju.core.map.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mbk.halatuju.core.model.ThemeMode

/**
 * Calm, high-contrast brand palette for HalaTuju.
 * Dynamic color is intentionally OFF so road-guidance affordances, maneuver banners,
 * and route lines remain consistent across every Android OEM and wallpaper.
 */
object HalaTujuPalette {
    val DeepTeal = Color(0xFF0E5A58)
    val DeepTealDark = Color(0xFF093E3C)
    val CrispMint = Color(0xFF39D2C0)
    val AmberAccent = Color(0xFFE59A24)
    val RouteBlueLight = Color(0xFF1A73E8)
    val RouteBlueDark = Color(0xFF5CA8FF)
    val RouteTraveledLight = Color(0xFF8CA0B3)
    val RouteTraveledDark = Color(0xFF475866)
    val SpeedLimitRed = Color(0xFFD92B2B)
    val GuidanceBannerBg = Color(0xFF0B4F4D)
    val GuidanceBannerContent = Color(0xFFFFFFFF)
}

private val LightColors = lightColorScheme(
    primary = HalaTujuPalette.DeepTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F1EE),
    onPrimaryContainer = Color(0xFF042726),
    secondary = HalaTujuPalette.AmberAccent,
    onSecondary = Color(0xFF1F1600),
    secondaryContainer = Color(0xFFFFEBC8),
    onSecondaryContainer = Color(0xFF2B1C00),
    tertiary = HalaTujuPalette.RouteBlueLight,
    onTertiary = Color.White,
    background = Color(0xFFF6F8F8),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFE5EBEB),
    onSurfaceVariant = Color(0xFF445252),
    outline = Color(0xFF728282),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = HalaTujuPalette.CrispMint,
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF094B49),
    onPrimaryContainer = Color(0xFFBCEFE8),
    secondary = Color(0xFFF6BC5C),
    onSecondary = Color(0xFF3E2900),
    secondaryContainer = Color(0xFF583D00),
    onSecondaryContainer = Color(0xFFFFE1AD),
    tertiary = HalaTujuPalette.RouteBlueDark,
    onTertiary = Color(0xFF002D63),
    background = Color(0xFF0F1415),
    onBackground = Color(0xFFE4EAE9),
    surface = Color(0xFF171E1F),
    onSurface = Color(0xFFE4EAE9),
    surfaceVariant = Color(0xFF283334),
    onSurfaceVariant = Color(0xFFB5C4C4),
    outline = Color(0xFF7E8E8E),
    error = Color(0xFFFF6B63),
    onError = Color(0xFF490005)
)

private val HalaTujuTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun HalaTujuTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        typography = HalaTujuTypography,
        content = content
    )
}
