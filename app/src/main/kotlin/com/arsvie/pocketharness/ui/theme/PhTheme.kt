package com.arsvie.pocketharness.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * UI-lab theme system: one app, four looks, swappable at runtime (Settings -> Appearance).
 * App-only; `:core` never sees colors. Screens and the themed primitives in this package read
 * [LocalPhTheme] — no color is hard-coded outside `PhThemes.kt`.
 *
 *  - GINGERBREAD — 2010-era Android chrome, skeuomorphic (the original look, rebuilt)
 *  - MINIMAL     — de-slopped modern light (no gradients, no neon, hairline surfaces)
 *  - TOMORROW    — 4chan board dark (the manga-pipeline palette: #1d1f21 / #c5c8c6 / #81a2be)
 *  - STUDIO      — AI-native primitives, crafted chips + traces (beautifului.dev-inspired)
 */
enum class PhLook { GINGERBREAD, MINIMAL, TOMORROW, STUDIO }

/** How the top bar renders. */
enum class PhBar { GRADIENT, FLAT, BANNER, SOFT }

/** How panels render. */
enum class PhCard { BEVEL, HAIRLINE, FILLED, SOFT }

/** How action buttons render. */
enum class PhButton { GLOSS, SOLID, BOARD, SOFT }

/** How messages render. */
enum class PhMessage { BUBBLE_BEVEL, BUBBLE_FLAT, POST, QUIET }

/** How code / tool-output panels render. */
enum class PhMono { TERMINAL, INSET_LIGHT, BOARD_DARK, SOFT_INSET }

/** How section headers render. */
enum class PhHeader { BAND, CAPS, LEGEND, LABEL }

/** Who authored a message block. */
enum class PhRole { USER, ASSISTANT }

/** Semantic colors; one instance per look. Screens only ever read these. */
data class PhColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val hairline: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val heading: Color,
    val note: Color,
    val accent: Color,
    val accentText: Color,
    val accentTint: Color,
    val link: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val run: Color,
    val userBg: Color,
    val userText: Color,
    val agentBg: Color,
    val agentText: Color,
    val thinkBg: Color,
    val thinkText: Color,
    val thinkLine: Color,
    val codeBg: Color,
    val codeText: Color,
    val codeErr: Color,
    val errTint: Color,
    val warnTint: Color,
    val barTop: Color,
    val barBottom: Color,
    val onBar: Color,
    val onBarDim: Color,
)

data class PhShape(
    val card: Dp,
    val small: Dp,
    val chip: Dp,
    val status: Dp,
)

data class PhType(
    val title: TextUnit,
    val body: TextUnit,
    val meta: TextUnit,
    val mono: TextUnit,
    val titleWeight: FontWeight,
    val titleSpacing: TextUnit,
    val emboss: Boolean,
)

data class PhTheme(
    val id: PhLook,
    val name: String,
    val blurb: String,
    val dark: Boolean,
    val colors: PhColors,
    val shape: PhShape,
    val type: PhType,
    val bar: PhBar,
    val card: PhCard,
    val button: PhButton,
    val message: PhMessage,
    val mono: PhMono,
    val header: PhHeader,
)

val LocalPhTheme = staticCompositionLocalOf<PhTheme> { error("PhTheme not provided") }

@Composable
fun PhThemeRoot(theme: PhTheme, content: @Composable () -> Unit) {
    val scheme = remember(theme.id) { theme.materialScheme() }
    val shapes = remember(theme.id) {
        Shapes(
            extraSmall = RoundedCornerShape(theme.shape.chip),
            small = RoundedCornerShape(theme.shape.small),
            medium = RoundedCornerShape(theme.shape.card),
            large = RoundedCornerShape(theme.shape.card),
            extraLarge = RoundedCornerShape(theme.shape.card),
        )
    }
    CompositionLocalProvider(LocalPhTheme provides theme) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = Typography(), content = content)
    }
}

/** Maps the look onto M3 roles so stock components (dialogs, fields, ripples) follow the theme. */
private fun PhTheme.materialScheme(): ColorScheme = if (dark) {
    darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.accentText,
        primaryContainer = colors.accentTint,
        onPrimaryContainer = colors.text,
        secondary = colors.textDim,
        onSecondary = colors.surface,
        tertiary = colors.link,
        onTertiary = colors.surface,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surfaceAlt,
        onSurfaceVariant = colors.textDim,
        error = colors.err,
        onError = Color.White,
        errorContainer = colors.errTint,
        onErrorContainer = colors.text,
        outline = colors.border,
        outlineVariant = colors.hairline,
    )
} else {
    lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.accentText,
        primaryContainer = colors.accentTint,
        onPrimaryContainer = colors.text,
        secondary = colors.textDim,
        onSecondary = colors.surface,
        tertiary = colors.link,
        onTertiary = colors.surface,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surfaceAlt,
        onSurfaceVariant = colors.textDim,
        error = colors.err,
        onError = Color.White,
        errorContainer = colors.errTint,
        onErrorContainer = colors.text,
        outline = colors.border,
        outlineVariant = colors.hairline,
    )
}
