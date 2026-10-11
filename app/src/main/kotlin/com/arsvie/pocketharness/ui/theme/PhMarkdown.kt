package com.arsvie.pocketharness.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import ph.ui.Markdown
import ph.ui.MdBlock
import ph.ui.MdSpan

/**
 * Chat markdown: the blocks parsed by `:core`'s [Markdown] rendered with this look's tokens.
 * Paragraphs, headings and list markers follow the message's [fontSize]/[lineHeight]; code uses
 * the mono token on the code surface, tinted per look like the tool cards. Selection stays the
 * caller's concern — the message block wraps this in its own SelectionContainer.
 */
@Composable
fun PhMarkdown(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit? = null,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    val blocks = remember(text) { Markdown.parse(text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> PhMdText(block.spans, color, fontSize, lineHeight)

                is MdBlock.Heading -> Text(
                    styled(block.spans, c),
                    color = c.heading,
                    fontSize = fontSize * headingScale(block.level),
                    fontWeight = FontWeight.Bold,
                )

                is MdBlock.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(t.shape.small))
                        .background(c.codeBg)
                        .border(1.dp, c.hairline, RoundedCornerShape(t.shape.small))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    Text(
                        block.text,
                        color = c.codeText,
                        fontSize = t.type.mono,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = t.type.mono * 1.4f,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }

                is MdBlock.Bullet -> Row(
                    Modifier.fillMaxWidth().padding(start = (block.depth.coerceAtMost(3) * 14).dp),
                ) {
                    Text("•", color = c.textDim, fontSize = fontSize)
                    Spacer(Modifier.width(7.dp))
                    PhMdText(block.spans, color, fontSize, lineHeight)
                }

                is MdBlock.Numbered -> Row(
                    Modifier.fillMaxWidth().padding(start = (block.depth.coerceAtMost(3) * 14).dp),
                ) {
                    Text(block.marker, color = c.textDim, fontSize = fontSize)
                    Spacer(Modifier.width(7.dp))
                    PhMdText(block.spans, color, fontSize, lineHeight)
                }

                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.border))
                    Spacer(Modifier.width(8.dp))
                    Text(styled(block.spans, c), color = c.textDim, fontSize = fontSize, lineHeight = lineHeight ?: TextUnit.Unspecified)
                }

                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 2.dp), color = c.hairline)
            }
        }
    }
}

@Composable
private fun PhMdText(spans: List<MdSpan>, color: Color, fontSize: TextUnit, lineHeight: TextUnit?) {
    Text(
        styled(spans, LocalPhTheme.current.colors),
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight ?: TextUnit.Unspecified,
    )
}

/** Headings stay small on a phone: # is the biggest the sheet ever prints, ### the common one. */
private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.35f
    2 -> 1.18f
    3 -> 1.06f
    else -> 1.0f
}

private fun styled(spans: List<MdSpan>, c: PhColors): AnnotatedString = buildAnnotatedString { appendSpans(spans, c) }

private fun AnnotatedString.Builder.appendSpans(spans: List<MdSpan>, c: PhColors) {
    spans.forEach { span ->
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendSpans(span.spans, c) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendSpans(span.spans, c) }
            is MdSpan.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBg, color = c.codeText),
            ) { append(span.text) }
            is MdSpan.Link -> withStyle(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline)) {
                append(span.text)
            }
        }
    }
}
