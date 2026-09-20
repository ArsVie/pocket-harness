package com.arsvie.pocketharness.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.ui.ApprovalPrompt
import ph.ui.Block

/**
 * The conversation blocks, one implementation per look: message bubbles / board posts / quiet
 * chat; the reasoning block (collapsible; a spoiler bar on Tomorrow); the tool card (collapsible,
 * output truncated by default); the approval card; and the composer.
 */

/** Message block. [meta] carries the board post number on Tomorrow; unused elsewhere. */
@Composable
fun PhMessageBlock(role: PhRole, text: String, queued: Boolean = false, meta: String? = null) {
    val t = LocalPhTheme.current
    val c = t.colors
    val isUser = role == PhRole.USER
    when (t.message) {
        PhMessage.BUBBLE_BEVEL -> {
            val shape = RoundedCornerShape(t.shape.card + 2.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                Column(
                    Modifier
                        .weight(0.86f, fill = false)
                        .clip(shape)
                        .background(
                            if (isUser) {
                                Brush.verticalGradient(listOf(lerp(c.userBg, Color.White, 0.35f), c.userBg))
                            } else {
                                Brush.verticalGradient(listOf(c.agentBg, c.surfaceAlt))
                            },
                        )
                        .border(1.dp, c.border, shape)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    QueuedLabel(queued, if (isUser) c.userText else c.agentText, t.type.meta)
                    SelectionContainer {
                        Text(text, color = if (isUser) c.userText else c.agentText, fontSize = t.type.body)
                    }
                }
            }
        }

        PhMessage.BUBBLE_FLAT -> {
            val shape = RoundedCornerShape(t.shape.card)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                Column(
                    Modifier
                        .weight(0.86f, fill = false)
                        .clip(shape)
                        .background(if (isUser) c.userBg else c.agentBg, shape)
                        .border(1.dp, c.hairline, shape)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    QueuedLabel(queued, if (isUser) c.userText else c.agentText, t.type.meta)
                    SelectionContainer {
                        Text(text, color = if (isUser) c.userText else c.agentText, fontSize = t.type.body)
                    }
                }
            }
        }

        PhMessage.POST -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface, RoundedCornerShape(t.shape.card))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isUser) "You" else "Assistant",
                        color = if (isUser) c.text else c.link,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (meta != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(meta, color = c.textFaint, fontSize = t.type.meta)
                    }
                    if (queued) {
                        Spacer(Modifier.width(6.dp))
                        Text("(queued)", color = c.textFaint, fontSize = t.type.meta)
                    }
                }
                Spacer(Modifier.height(5.dp))
                SelectionContainer {
                    Text(text, color = c.text, fontSize = t.type.body, lineHeight = t.type.body * 1.5f)
                }
            }
        }

        PhMessage.QUIET -> if (isUser) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(
                    Modifier
                        .weight(0.86f, fill = false)
                        .clip(RoundedCornerShape(t.shape.card))
                        .background(c.userBg)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    QueuedLabel(queued, c.userText, t.type.meta)
                    SelectionContainer { Text(text, color = c.userText, fontSize = t.type.body) }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "ASSISTANT",
                    color = c.textFaint,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(text, color = c.agentText, fontSize = t.type.body, lineHeight = t.type.body * 1.45f)
                }
            }
        }
    }
}

@Composable
private fun QueuedLabel(queued: Boolean, color: Color, size: TextUnit) {
    if (!queued) return
    Column {
        Text("queued — delivered at the next step", color = color.copy(alpha = 0.75f), fontSize = size)
        Spacer(Modifier.height(3.dp))
    }
}

/** Reasoning block. Collapsed by default; text truncated on expand. */
@Composable
fun PhThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val t = LocalPhTheme.current
    val c = t.colors
    val count = kCount(text.length)
    if (t.id == PhLook.TOMORROW) {
        if (!expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(t.shape.chip))
                    .background(c.thinkBg)
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "spoiler ▸ reasoning ($count chars) — tap to reveal",
                    color = Color(0xFF585858),
                    fontSize = 11.5.sp,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(t.shape.chip))
                    .background(c.surfaceAlt)
                    .border(1.dp, c.border, RoundedCornerShape(t.shape.chip))
                    .padding(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "[reasoning]",
                        color = c.note,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$count chars", color = c.textFaint, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                ExpandableText(
                    text,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                    color = c.text,
                    maxLines = 12,
                    linkColor = c.link,
                )
            }
        }
        return
    }
    val shape = RoundedCornerShape(t.shape.card)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.thinkBg)
            .border(1.dp, c.thinkLine, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhStatusSquare(c.thinkText.copy(alpha = 0.55f), 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Reasoning",
                color = c.thinkText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("$count chars", color = c.thinkText.copy(alpha = 0.7f), fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = c.thinkText.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            HorizontalDivider(color = c.thinkLine)
            ExpandableText(
                text,
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = c.thinkText,
                maxLines = 12,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** One tool call. Collapsed: a single line. Expanded: output, truncated by default. */
@Composable
fun PhToolCard(call: Block.ToolCall, plain: Boolean = false) {
    var expanded by remember { mutableStateOf(call.expandedByDefault) }
    val t = LocalPhTheme.current
    val c = t.colors
    val status = when {
        call.isError -> c.err
        call.output == null -> c.run
        else -> c.ok
    }
    val shape = RoundedCornerShape(t.shape.card)
    val chrome = if (plain) {
        Modifier
    } else {
        when (t.card) {
            PhCard.BEVEL -> Modifier
                .clip(shape)
                .background(Brush.verticalGradient(listOf(c.surface, c.surfaceAlt)))
                .border(1.dp, c.borderStrong, shape)

            PhCard.HAIRLINE -> Modifier.clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
            PhCard.FILLED -> Modifier.background(c.surface, shape)
            PhCard.SOFT -> Modifier.clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
        }
    }
    Column(Modifier.fillMaxWidth().then(chrome)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhStatusSquare(status, 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                call.name,
                color = if (t.id == PhLook.TOMORROW) c.note else c.textDim,
                fontSize = t.type.mono,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                call.summary,
                color = c.text,
                fontSize = t.type.mono,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (call.output == null) {
                Spacer(Modifier.width(6.dp))
                Text("running", color = c.run, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = c.textFaint,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            HorizontalDivider(color = c.hairline)
            PhCodeBox(
                text = call.output?.takeIf { it.isNotBlank() } ?: "(no output yet)",
                isError = call.isError,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** A run of consecutive tool calls, folded into one chip-like card (Studio look). */
@Composable
fun PhToolGroup(calls: List<Block.ToolCall>) {
    var expanded by remember { mutableStateOf(false) }
    val t = LocalPhTheme.current
    val c = t.colors
    val status = when {
        calls.any { it.isError } -> c.err
        calls.any { it.output == null } -> c.run
        else -> c.ok
    }
    val names = calls.groupingBy { it.name }.eachCount().entries.joinToString(" · ") { "${it.key} ×${it.value}" }
    val shape = RoundedCornerShape(t.shape.card)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhStatusSquare(status, 8.dp)
            Spacer(Modifier.width(8.dp))
            Text("${calls.size} tool calls", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                names,
                color = c.textDim,
                fontSize = t.type.mono,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = c.textFaint,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            HorizontalDivider(color = c.hairline)
            calls.forEachIndexed { i, call ->
                if (i > 0) HorizontalDivider(color = c.hairline)
                PhToolCard(call, plain = true)
            }
        }
    }
}

/** Approval as a permission card (not a modal). */
@Composable
fun PhApprovalCard(prompt: ApprovalPrompt, onDecide: (Boolean) -> Unit) {
    val t = LocalPhTheme.current
    val c = t.colors
    when (t.button) {
        PhButton.GLOSS -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.shape.card))
                .background(c.surface)
                .border(1.dp, c.borderStrong, RoundedCornerShape(t.shape.card)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFFC8861F), Color(0xFF9A6414))))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("Approval needed", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(12.dp)) {
                ApprovalBody(prompt)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PhButton("Deny", { onDecide(false) })
                    Spacer(Modifier.width(8.dp))
                    PhButton("Allow", { onDecide(true) }, primary = true)
                }
            }
        }

        PhButton.SOLID -> PhCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhStatusSquare(c.warn, 9.dp)
                Spacer(Modifier.width(8.dp))
                Text("Approval needed", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            ApprovalBody(prompt)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PhButton("Deny", { onDecide(false) })
                Spacer(Modifier.width(8.dp))
                PhButton("Allow", { onDecide(true) }, primary = true)
            }
        }

        PhButton.BOARD -> Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(t.shape.card))
                .border(1.dp, c.border, RoundedCornerShape(t.shape.card))
                .padding(12.dp),
        ) {
            Text("Approval required", color = c.heading, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            ApprovalBody(prompt)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PhButton("Deny", { onDecide(false) })
                Spacer(Modifier.width(8.dp))
                PhButton("Allow", { onDecide(true) }, primary = true)
            }
        }

        PhButton.SOFT -> PhCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhStatusSquare(c.warn, 9.dp)
                Spacer(Modifier.width(8.dp))
                Text("Approval needed", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            ApprovalBody(prompt)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PhButton("Deny", { onDecide(false) })
                Spacer(Modifier.width(8.dp))
                PhButton("Allow", { onDecide(true) }, primary = true)
            }
        }
    }
}

@Composable
private fun ApprovalBody(prompt: ApprovalPrompt) {
    val t = LocalPhTheme.current
    val c = t.colors
    Column {
        Text(
            "The turn wants to run commands in an untrusted folder:",
            color = c.textDim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            prompt.cwd,
            color = c.text,
            fontFamily = FontFamily.Monospace,
            fontSize = t.type.mono,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        PhCodeBox(prompt.command, isError = false, maxLines = 6)
    }
}

/** Composer: text field + one slot that morphs Send <-> Stop. */
@Composable
fun PhComposer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val t = LocalPhTheme.current
    val c = t.colors
    val fieldShape = RoundedCornerShape(
        when (t.id) {
            PhLook.GINGERBREAD -> 4.dp
            PhLook.MINIMAL -> 10.dp
            PhLook.TOMORROW -> 2.dp
            PhLook.STUDIO -> 14.dp
        },
    )
    val containerBg = if (t.id == PhLook.TOMORROW) c.bg else c.surface
    val fieldBg = when (t.id) {
        PhLook.TOMORROW -> c.surfaceAlt
        PhLook.GINGERBREAD -> Color(0xFFFFFFFF)
        else -> c.surfaceAlt
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(containerBg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        PhDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (running) "Steer the running turn…" else "Message",
                        color = c.textFaint,
                        fontSize = t.type.body,
                    )
                },
                shape = fieldShape,
                maxLines = 4,
                textStyle = TextStyle(fontSize = t.type.body, color = c.text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = fieldBg,
                    unfocusedContainerColor = fieldBg,
                    focusedBorderColor = if (t.id == PhLook.TOMORROW) c.border else c.accent,
                    unfocusedBorderColor = c.border,
                    cursorColor = c.accent,
                    focusedTextColor = c.text,
                    unfocusedTextColor = c.text,
                ),
            )
            Spacer(Modifier.width(8.dp))
            val send = { if (draft.isNotBlank()) { onSend(draft); draft = "" } }
            when (t.button) {
                PhButton.GLOSS -> if (running) {
                    PhButton("Stop", onStop, danger = true)
                } else {
                    PhButton("Send", send, primary = true, enabled = draft.isNotBlank())
                }

                PhButton.BOARD -> if (running) {
                    PhButton("Stop", onStop, danger = true)
                } else {
                    PhButton("Post", send, primary = true, enabled = draft.isNotBlank())
                }

                PhButton.SOLID -> RoundAction(
                    running = running,
                    enabled = draft.isNotBlank(),
                    radius = 10.dp,
                    idleColor = c.accent,
                    onSend = send,
                    onStop = onStop,
                )

                PhButton.SOFT -> RoundAction(
                    running = running,
                    enabled = draft.isNotBlank(),
                    radius = 22.dp,
                    idleColor = c.accent,
                    onSend = send,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun RoundAction(
    running: Boolean,
    enabled: Boolean,
    radius: androidx.compose.ui.unit.Dp,
    idleColor: Color,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = LocalPhTheme.current.colors
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        running -> c.err
        enabled -> idleColor
        else -> c.border
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(radius))
            .background(bg, RoundedCornerShape(radius))
            .clickable(interactionSource = interaction, indication = null) {
                if (running) onStop() else if (enabled) onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(Modifier.size(12.dp).background(Color.White, RoundedCornerShape(2.dp)))
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
