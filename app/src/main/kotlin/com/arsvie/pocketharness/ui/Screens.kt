package com.arsvie.pocketharness.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arsvie.pocketharness.SettingsField
import com.arsvie.pocketharness.ShellInfo
import kotlinx.coroutines.flow.collect
import ph.policy.ExecutionMode
import ph.ui.ApprovalPrompt
import ph.ui.Block
import ph.ui.OpenThread
import ph.ui.SettingsState
import ph.ui.ThreadRow

/**
 * The v2 screens (ADR-007, superseding the 2010-era chrome of ADR-005 §1). Values come verbatim
 * from the frozen `ph.ui` types; no logic here. Three screens — threads, thread, settings — with
 * real navigation (back + gear), pastel light theme (see Theme.kt), lazy lists, collapsible
 * thinking/tool blocks, and a composer that morphs Send ↔ Stop.
 */

private val Mono = FontFamily.Monospace

/** A solid rounded square — the one status indicator shape this UI uses (never a circle). */
@Composable
fun StatusSquare(color: Color, size: Dp = 9.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).background(color, RoundedCornerShape(3.dp)))
}

/** Shared top bar: optional back, title (+optional subtitle), optional trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions?.invoke(this)
            Spacer(Modifier.width(6.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Small status pill: square + label, tinted by tone. Used for running / waiting states. */
@Composable
fun StatusPill(text: String, tone: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusSquare(tone)
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tone)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 1.6,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** The card: white, hairline border, 16dp radius. The v2 surface unit. */
@Composable
fun PhCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis).toString()

// ---------------------------------------------------------------- threads list

@Composable
fun ThreadsScreen(
    threads: List<ThreadRow>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(
            title = "Threads",
            subtitle = if (threads.isEmpty()) null else "${threads.size} session${if (threads.size == 1) "" else "s"}",
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Box(modifier = Modifier.weight(1f)) {
            if (threads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = "No threads yet",
                        body = "Start one with the + button.\nMessages, tool calls and thinking all live here.",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(threads, key = { it.id }) { row ->
                        ThreadRowCard(row, onOpen)
                    }
                }
            }
            FloatingActionButton(
                onClick = onNew,
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New thread")
            }
        }
    }
}

@Composable
private fun ThreadRowCard(row: ThreadRow, onOpen: (String) -> Unit) {
    PhCard(onClick = { onOpen(row.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = relativeTime(row.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- thread view

@Composable
fun ThreadScreen(
    open: OpenThread?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDecideApproval: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(
            title = open?.title ?: "Thread",
            subtitle = open?.statusLine,
            onBack = onBack,
            actions = {
                if (open != null) {
                    val yolo = open.mode == ExecutionMode.YOLO
                    val tone = if (yolo) PhPalette.Wait else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        text = open.mode.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = tone,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(tone.copy(alpha = 0.12f))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            },
        )

        if (open == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState("No thread open", "Pick one from the list, or start a new one.")
            }
            return
        }

        val approval = open.pendingApproval
        val itemCount = open.blocks.size + (if (approval != null) 1 else 0)
        val listState = rememberLazyListState()

        // Auto-follow: jump to the bottom on open; afterwards follow new blocks only while the
        // user is already at the bottom (scrolling up must not get yanked back down).
        var initialized by remember(open.id) { mutableStateOf(false) }
        var wasAtBottom by remember(open.id) { mutableStateOf(true) }
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount
            }.collect { (last, total) ->
                wasAtBottom = last == null || last >= total - 1
            }
        }
        LaunchedEffect(itemCount) {
            if (itemCount > 0 && (!initialized || wasAtBottom)) {
                if (initialized) {
                    listState.animateScrollToItem(itemCount - 1)
                } else {
                    listState.scrollToItem(itemCount - 1)
                    initialized = true
                }
            }
        }

        if (open.running || approval != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (approval != null) StatusPill("Waiting for you", PhPalette.Wait)
                else StatusPill("Running…", PhPalette.Busy)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (open.blocks.isEmpty() && approval == null) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState("Fresh thread", "Send a message to start the turn.")
                    }
                }
            }
            itemsIndexed(open.blocks, key = { i, _ -> "block-$i" }) { _, block ->
                when (block) {
                    is Block.UserText -> UserBubble(block.text, block.queued)
                    is Block.AssistantText -> AssistantBubble(block.text)
                    is Block.Thinking -> ThinkingBlock(block.text)
                    is Block.ToolCall -> ToolCard(block)
                }
            }
            if (approval != null) {
                item(key = "approval") { ApprovalCard(approval, onDecideApproval) }
            }
        }

        Composer(running = open.running, onSend = onSend, onStop = onStop)
    }
}

@Composable
private fun UserBubble(text: String, queued: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .weight(0.86f, fill = false)
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(
                    if (queued) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.primaryContainer,
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            if (queued) {
                Text(
                    text = "queued — delivered at the next step",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(3.dp))
            }
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .weight(0.86f, fill = false)
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PhPalette.Thinking)
            .border(1.dp, PhPalette.OnThinking.copy(alpha = 0.16f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusSquare(PhPalette.OnThinking.copy(alpha = 0.55f))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Thinking",
                style = MaterialTheme.typography.labelMedium,
                color = PhPalette.OnThinking,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = PhPalette.OnThinking.copy(alpha = 0.7f),
            )
        }
        if (expanded) {
            HorizontalDivider(color = PhPalette.OnThinking.copy(alpha = 0.14f))
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = PhPalette.OnThinking,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(call: Block.ToolCall) {
    var expanded by remember { mutableStateOf(call.expandedByDefault) }
    val status = when {
        call.isError -> MaterialTheme.colorScheme.error
        call.output == null -> PhPalette.Busy
        else -> PhPalette.Ok
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusSquare(status)
            Spacer(Modifier.width(8.dp))
            Text(
                text = call.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = call.summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SelectionContainer {
                Text(
                    text = call.output?.takeIf { it.isNotBlank() } ?: "(no output yet)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    color = if (call.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp),
                )
            }
        }
    }
}

/** The approval as a permission card (not a red wall, not a modal). */
@Composable
private fun ApprovalCard(prompt: ApprovalPrompt, onDecide: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusSquare(PhPalette.Wait)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Permission needed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The turn wants to run commands in an untrusted folder:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = prompt.cwd,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = prompt.command,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onDecide(false) }) { Text("Deny") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { onDecide(true) }) { Text("Allow") }
        }
    }
}

@Composable
fun Composer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (running) "Steer the running turn…" else "Message",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Spacer(Modifier.width(8.dp))
            // One slot, two faces: Send when idle, Stop while the turn runs.
            AnimatedContent(targetState = running, label = "send-stop") { isRunning ->
                if (isRunning) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp)),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = { onSend(draft); draft = "" },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(
    settings: SettingsState,
    shell: ShellInfo?,
    onBack: () -> Unit,
    onModeChange: (ExecutionMode) -> Unit,
    onEdit: (SettingsField, String) -> Unit,
) {
    var editing by remember { mutableStateOf<SettingsField?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(title = "Settings", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "execution") {
                SectionLabel("Execution")
                PhCard {
                    ModeSelector(mode = settings.mode, onChange = onModeChange)
                }
            }
            item(key = "model") {
                SectionLabel("Model")
                PhCard {
                    SettingRow(
                        label = "Base URL",
                        value = settings.baseUrl.ifBlank { "not set" },
                        mono = true,
                        onClick = { editing = SettingsField.BASE_URL },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        label = "Model",
                        value = settings.model.ifBlank { "not set" },
                        mono = true,
                        onClick = { editing = SettingsField.MODEL },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Reasoning effort",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        settings.reasoningEfforts.forEach { effort ->
                            EffortChip(
                                label = effort,
                                selected = effort == settings.reasoningEffort,
                                onClick = { onEdit(SettingsField.REASONING_EFFORT, effort) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
            item(key = "credentials") {
                SectionLabel("Credentials")
                PhCard {
                    SettingRow(
                        label = "API key",
                        value = if (settings.hasApiKey) "Stored in the Android Keystore" else "Not set — tap to add",
                        chip = if (settings.hasApiKey) PhPalette.Ok else PhPalette.Wait,
                        onClick = { editing = SettingsField.API_KEY },
                    )
                }
            }
            item(key = "diagnostics") {
                SectionLabel("Diagnostics")
                PhCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusSquare(if (shell != null) PhPalette.Ok else PhPalette.Wait)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Shell",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = shell?.title ?: "checking…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (shell != null) {
                                Text(
                                    text = shell.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = Mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { field ->
        var draft by remember(field) {
            mutableStateOf(
                when (field) {
                    SettingsField.BASE_URL -> settings.baseUrl
                    SettingsField.MODEL -> settings.model
                    SettingsField.REASONING_EFFORT -> settings.reasoningEffort.orEmpty()
                    SettingsField.API_KEY -> ""
                },
            )
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(text = titleOf(field), style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    if (field == SettingsField.API_KEY) {
                        Text(
                            text = "Never logged; stored encrypted in the Android Keystore.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(field, draft)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModeSelector(mode: ExecutionMode, onChange: (ExecutionMode) -> Unit) {
    Column {
        ModeOption(
            title = "DEFAULT",
            subtitle = "Per-folder trust · approvals on writes",
            selected = mode == ExecutionMode.DEFAULT,
            accent = MaterialTheme.colorScheme.primary,
            onClick = { onChange(ExecutionMode.DEFAULT) },
        )
        Spacer(Modifier.height(8.dp))
        ModeOption(
            title = "YOLO",
            subtitle = "Runs without approval · the policy floor still applies",
            selected = mode == ExecutionMode.YOLO,
            accent = PhPalette.Wait,
            onClick = { onChange(ExecutionMode.YOLO) },
        )
    }
}

@Composable
private fun ModeOption(title: String, subtitle: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = accent)
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    mono: Boolean = false,
    chip: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (mono) Mono else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chip != null) {
            Spacer(Modifier.width(8.dp))
            StatusSquare(chip)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EffortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun titleOf(field: SettingsField): String = when (field) {
    SettingsField.BASE_URL -> "Base URL"
    SettingsField.MODEL -> "Model"
    SettingsField.REASONING_EFFORT -> "Reasoning effort (blank = omit)"
    SettingsField.API_KEY -> "API key"
}
