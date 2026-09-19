package com.arsvie.pocketharness.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PocketHarness v2 theme — a soft pastel light scheme (ADR-007, superseding the 2010-era chrome of
 * ADR-005 §1). One theme, deliberately: no dark mode. `:app` only; `:core` never sees colors.
 *
 * The palette is the visible half of the v2 UI decision; the other half (navigation, list
 * behaviour, card anatomy) lives in `Screens.kt`.
 */
private val PastelLightScheme = lightColorScheme(
    primary = Color(0xFF6B5AC7), // periwinkle — primary actions, running state
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E3FB), // user bubbles, selection fills
    onPrimaryContainer = Color(0xFF2A2153),
    secondary = Color(0xFF3F8F82), // teal — secondary accents
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6F0E9),
    onSecondaryContainer = Color(0xFF103B33),
    tertiary = Color(0xFFB85F8C), // rose — thinking accents
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFADCEA),
    onTertiaryContainer = Color(0xFF4A1631),
    error = Color(0xFFA93B52), // muted rose-red, not the alarm red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDDE2),
    onErrorContainer = Color(0xFF5A1523),
    background = Color(0xFFF8F6FC),
    onBackground = Color(0xFF232029),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF232029),
    surfaceVariant = Color(0xFFEFEBF7),
    onSurfaceVariant = Color(0xFF5B5666),
    outline = Color(0xFFC8C2D6),
    outlineVariant = Color(0xFFE5E1EF),
)

/** Semantic colors the Material scheme does not carry (tool status squares, thinking tone). */
object PhPalette {
    /** Tool call finished without error. */
    val Ok = Color(0xFF4C9B7F)

    /** Tool call still running / turn in flight. */
    val Busy = Color(0xFF6B5AC7)

    /** Attention: waiting for the user (approval, errors). */
    val Wait = Color(0xFFC98A2B)

    /** Thinking block background (soft butter). */
    val Thinking = Color(0xFFFDF6E3)
    val OnThinking = Color(0xFF5C5325)
}

private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PocketHarnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PastelLightScheme,
        shapes = PocketShapes,
        typography = Typography(),
        content = content,
    )
}
