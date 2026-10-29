package ir.tafakoritech.adminapplication.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ── Dark scheme — deep navy with green accents ────────────────────────────────

private val CommanderDarkColorScheme = darkColorScheme(
    primary = FreshGreen,
    onPrimary = DeepNavy,
    primaryContainer = GreenContainer,
    onPrimaryContainer = BrightGreen,
    inversePrimary = GreenContainer,
    secondary = BrightGreen,
    onSecondary = DeepNavy,
    secondaryContainer = Navy700,
    onSecondaryContainer = OffWhite,
    tertiary = BrightGreen,
    onTertiary = DeepNavy,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = BrightGreen,
    background = DeepNavy,
    onBackground = OffWhite,
    surface = DeepNavy,
    onSurface = OffWhite,
    surfaceVariant = Navy800,
    onSurfaceVariant = SlateMuted,
    surfaceTint = FreshGreen,
    surfaceContainerLowest = NavyDeepest,
    surfaceContainerLow = Navy850,
    surfaceContainer = Navy800,
    surfaceContainerHigh = Color(0xFF243247),
    surfaceContainerHighest = Color(0xFF2B3B54),
    surfaceDim = NavyDeepest,
    surfaceBright = Navy700,
    inverseSurface = OffWhite,
    inverseOnSurface = DeepNavy,
    error = ErrorRed,
    onError = DeepNavy,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = Navy600,
    outlineVariant = Navy700,
    scrim = Color.Black,
)

// ── Light scheme — white/slate surfaces with green accents ────────────────────

private val LightSurface        = Color(0xFFFFFFFF)
private val LightBackground     = OffWhite                  // #F8FAFC
private val LightSurfaceVariant = Color(0xFFE8EDF5)
private val LightOnSurfaceVar   = Navy700                   // #334155
private val LightPrimaryContainer = Color(0xFFB7F0C8)
private val LightOnPrimaryContainer = Color(0xFF003917)
private val LightSecondaryContainer = Color(0xFFDCFCE7)
private val LightOnSecondaryContainer = Color(0xFF14532D)
private val LightError          = Color(0xFFD32F2F)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnError        = Color(0xFFFFFFFF)
private val LightOnErrorContainer = Color(0xFF7F1D1D)
private val LightOutline        = SlateMuted                // #94A3B8
private val LightOutlineVariant = Color(0xFFCBD5E1)

private val CommanderLightColorScheme = lightColorScheme(
    primary = FreshGreen,
    onPrimary = LightSurface,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = BrightGreen,
    secondary = Color(0xFF16A34A),
    onSecondary = LightSurface,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = BrightGreen,
    onTertiary = DeepNavy,
    tertiaryContainer = LightSecondaryContainer,
    onTertiaryContainer = LightOnSecondaryContainer,
    background = LightBackground,
    onBackground = DeepNavy,
    surface = LightSurface,
    onSurface = DeepNavy,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVar,
    surfaceTint = FreshGreen,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = OffWhite,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = Color(0xFFD9E2EF),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    surfaceDim = Color(0xFFD1D9E4),
    surfaceBright = LightSurface,
    inverseSurface = Navy800,
    inverseOnSurface = OffWhite,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color.Black,
)

// ── Shapes ────────────────────────────────────────────────────────────────────

private val CommanderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun CommanderTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colorScheme = if (darkTheme) CommanderDarkColorScheme else CommanderLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = CommanderShapes,
        content = content,
    )
}
