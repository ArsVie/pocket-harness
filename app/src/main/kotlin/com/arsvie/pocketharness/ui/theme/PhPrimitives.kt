package com.arsvie.pocketharness.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Themed UI primitives. Every look branch lives here — Screens.kt composes these and never
 * hard-codes a color. Four looks, one call-site.
 */

/** The one status shape this UI uses: a solid rounded square (never a circle). */
@Composable
fun PhStatusSquare(color: Color, size: Dp = 9.dp) {
    val radius = LocalPhTheme.current.shape.status
    Box(Modifier.size(size).background(color, RoundedCornerShape(radius)))
}

/** A borderless icon action for top bars. */
@Composable
fun PhIconAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null) {
    val c = LocalPhTheme.current.colors
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint ?: c.textDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun PhDivider() {
    HorizontalDivider(color = LocalPhTheme.current.colors.hairline)
}

/** Shared top bar: optional back, title (+optional subtitle), optional trailing actions. */
@Composable
fun PhBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    val titleStyle = TextStyle(
        fontSize = t.type.title,
        fontWeight = t.type.titleWeight,
        letterSpacing = t.type.titleSpacing,
        shadow = if (t.type.emboss) {
            Shadow(color = Color(0x99000000), offset = Offset(0f, 1.2f), blurRadius = 1f)
        } else {
            null
        },
    )
    when (t.bar) {
        PhBar.GRADIENT -> Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.barTop, c.barBottom)))
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    PhIconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, tint = c.onBar)
                } else {
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = titleStyle, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = t.type.meta, color = c.onBarDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                actions?.invoke(this)
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
        }

        PhBar.FLAT, PhBar.SOFT -> Column(
            Modifier
                .fillMaxWidth()
                .background(if (t.bar == PhBar.SOFT) c.surface else c.bg)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    PhIconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                } else {
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = titleStyle, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = t.type.meta, color = c.onBarDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                actions?.invoke(this)
                Spacer(Modifier.width(6.dp))
            }
            PhDivider()
        }

        PhBar.BANNER -> Column(
            Modifier.fillMaxWidth().background(c.bg).statusBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 56.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title, style = titleStyle, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = t.type.meta, color = c.onBarDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.align(Alignment.CenterStart)) {
                    if (onBack != null) PhIconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                }
                Row(Modifier.align(Alignment.CenterEnd)) { actions?.invoke(this) }
            }
            PhDivider()
        }
    }
}

/** Section header inside Settings / lists. */
@Composable
fun PhSectionHeader(text: String) {
    val t = LocalPhTheme.current
    val c = t.colors
    when (t.header) {
        PhHeader.BAND -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.shape.small))
                .background(Brush.verticalGradient(listOf(Color(0xFF6E6E6E), Color(0xFF4A4A4A))))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text.uppercase(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        PhHeader.CAPS -> Text(
            text.uppercase(),
            color = c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 2.dp),
        )

        PhHeader.LEGEND -> Text(text, color = c.heading, fontSize = 13.sp, fontWeight = FontWeight.Bold)

        PhHeader.LABEL -> Text(
            text.uppercase(),
            color = c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** Panel container. */
@Composable
fun PhCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    val shape = RoundedCornerShape(t.shape.card)
    val base = when (t.card) {
        PhCard.BEVEL -> Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.surface, c.surfaceAlt)))
            .border(1.dp, c.borderStrong, shape)

        PhCard.HAIRLINE -> Modifier.clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
        PhCard.FILLED -> Modifier.background(c.surface, shape)
        PhCard.SOFT -> Modifier.clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(base)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(if (t.card == PhCard.BEVEL) 12.dp else 14.dp),
        content = content,
    )
}

/** Action button. */
@Composable
fun PhButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha = if (enabled) 1f else 0.5f
    when (t.button) {
        PhButton.GLOSS -> {
            val top = when {
                danger -> Color(0xFFB23B3B)
                primary -> Color(0xFF6FA337)
                else -> Color(0xFF6E6E6E)
            }
            val bottom = when {
                danger -> Color(0xFF7E2727)
                primary -> Color(0xFF47701C)
                else -> Color(0xFF3F3F3F)
            }
            val topC = if (pressed) lerp(top, Color.Black, 0.22f) else top
            val botC = if (pressed) lerp(bottom, Color.Black, 0.22f) else bottom
            val shape = RoundedCornerShape(t.shape.small)
            Box(
                modifier = modifier
                    .sizeIn(minHeight = 38.dp)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(topC, botC)))
                    .border(1.dp, Color(0xFF2E2E2E), shape)
                    .clickable(interactionSource = interaction, indication = null) { if (enabled) onClick() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White.copy(alpha = alpha), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        PhButton.SOLID, PhButton.SOFT -> {
            val bg = when {
                danger -> c.err
                primary -> c.accent
                else -> c.surface
            }
            val fg = when {
                danger || primary -> c.accentText
                else -> c.text
            }
            val shape = RoundedCornerShape(t.shape.small)
            Box(
                modifier = modifier
                    .sizeIn(minHeight = 40.dp)
                    .clip(shape)
                    .background(if (pressed) bg.copy(alpha = 0.86f) else bg, shape)
                    .then(
                        if (!primary && !danger) Modifier.border(1.dp, c.border, shape) else Modifier,
                    )
                    .clickable(interactionSource = interaction, indication = null) { if (enabled) onClick() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg.copy(alpha = alpha), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        PhButton.BOARD -> {
            val bg = if (pressed) c.accent else c.border
            val fg = if (pressed) c.accentText else c.text
            val shape = RoundedCornerShape(t.shape.chip)
            Box(
                modifier = modifier
                    .sizeIn(minHeight = 34.dp)
                    .clip(shape)
                    .background(bg, shape)
                    .border(1.dp, c.borderStrong, shape)
                    .clickable(interactionSource = interaction, indication = null) { if (enabled) onClick() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg.copy(alpha = alpha), fontSize = 13.sp)
            }
        }
    }
}

/** Selectable chip (reasoning effort and similar). */
@Composable
fun PhChip(label: String, selected: Boolean, onClick: (() -> Unit)? = null) {
    val t = LocalPhTheme.current
    val c = t.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.shape.chip)
    val bg: Color
    val fg: Color
    val border: Color
    when (t.button) {
        PhButton.GLOSS -> if (selected) {
            bg = Color(0xFF6FA337); fg = Color.White; border = Color(0xFF3E5F18)
        } else {
            bg = Color(0xFF6E6E6E); fg = Color.White; border = Color(0xFF3A3A3A)
        }

        PhButton.SOLID -> if (selected) {
            bg = c.text; fg = Color.White; border = c.text
        } else {
            bg = c.surface; fg = c.text; border = c.border
        }

        PhButton.BOARD -> if (selected) {
            bg = c.accent; fg = c.accentText; border = c.borderStrong
        } else {
            bg = c.border; fg = c.text; border = c.borderStrong
        }

        PhButton.SOFT -> if (selected) {
            bg = c.accentTint; fg = c.link; border = c.accent.copy(alpha = 0.45f)
        } else {
            bg = c.surface; fg = c.textDim; border = c.border
        }
    }
    val eff = if (pressed && t.button == PhButton.GLOSS) lerp(bg, Color.Black, 0.2f) else bg
    Box(
        modifier = Modifier
            .clip(shape)
            .background(eff, shape)
            .border(1.dp, border, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.5.sp,
            fontWeight = if (t.button == PhButton.GLOSS) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Truncated text with a "Show all" affordance. The one truncation mechanism of the app. */
@Composable
fun ExpandableText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int = 14,
    maxChars: Int = 2200,
    linkColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    var showAll by remember(text) { mutableStateOf(false) }
    val lines = remember(text) { text.split('\n') }
    val needsCut = lines.size > maxLines || text.length > maxChars
    val shown = if (showAll || !needsCut) {
        text
    } else {
        val byLines = lines.take(maxLines).joinToString("\n")
        if (byLines.length > maxChars) byLines.take(maxChars).trimEnd() + " …" else byLines + "\n…"
    }
    Column(modifier) {
        SelectionContainer { Text(shown, style = style, color = color) }
        if (needsCut && !showAll) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Show all ${lines.size} lines",
                style = style.copy(fontWeight = FontWeight.SemiBold),
                color = linkColor ?: LocalPhTheme.current.colors.link,
                modifier = Modifier.clickable { showAll = true }.padding(vertical = 2.dp),
            )
        }
    }
}

/** Monospace output panel. */
@Composable
fun PhCodeBox(text: String, isError: Boolean, modifier: Modifier = Modifier, maxLines: Int = 14) {
    val t = LocalPhTheme.current
    val c = t.colors
    val fg = if (isError) c.codeErr else c.codeText
    val shape = when (t.mono) {
        PhMono.TERMINAL -> RoundedCornerShape(3.dp)
        PhMono.INSET_LIGHT -> RoundedCornerShape(6.dp)
        PhMono.BOARD_DARK -> RoundedCornerShape(2.dp)
        PhMono.SOFT_INSET -> RoundedCornerShape(10.dp)
    }
    val borderColor = when (t.mono) {
        PhMono.TERMINAL -> Color(0xFF000000)
        PhMono.BOARD_DARK -> c.border
        else -> c.hairline
    }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = t.type.mono, lineHeight = t.type.mono * 1.45f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.codeBg)
            .border(1.dp, borderColor, shape)
            .padding(10.dp),
    ) {
        ExpandableText(text, style = style, color = fg, maxLines = maxLines, linkColor = fg)
    }
}

/** Status line: colored square + label. Used for running / waiting states. */
@Composable
fun PhStatusTag(text: String, tone: Color) {
    val t = LocalPhTheme.current
    val c = t.colors
    when (t.button) {
        PhButton.GLOSS -> Row(
            Modifier
                .clip(RoundedCornerShape(t.shape.small))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(t.shape.small))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhStatusSquare(tone, 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(text, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        PhButton.SOLID -> Row(verticalAlignment = Alignment.CenterVertically) {
            PhStatusSquare(tone, 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(text, color = tone, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        PhButton.BOARD -> Row(verticalAlignment = Alignment.CenterVertically) {
            PhStatusSquare(tone, 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(text.uppercase(), color = tone, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }

        PhButton.SOFT -> Row(
            Modifier
                .clip(RoundedCornerShape(t.shape.chip))
                .background(c.surface)
                .border(1.dp, c.hairline, RoundedCornerShape(t.shape.chip))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhStatusSquare(tone, 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(text, color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Empty state. */
@Composable
fun PhEmptyState(title: String, body: String) {
    val t = LocalPhTheme.current
    val c = t.colors
    val lean = t.button != PhButton.SOLID
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (lean) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (lean) {
            PhStatusSquare(c.textFaint, 12.dp)
            Spacer(Modifier.height(10.dp))
        }
        Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            color = c.textDim,
            fontSize = 13.sp,
            textAlign = if (lean) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** 1.2k-style count. */
fun kCount(n: Int): String = if (n < 1000) "$n" else "${n / 1000}.${(n % 1000) / 100}k"
