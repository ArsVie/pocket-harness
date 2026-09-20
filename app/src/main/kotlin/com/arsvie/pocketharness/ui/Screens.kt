package com.arsvie.pocketharness.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arsvie.pocketharness.BuildConfig
import com.arsvie.pocketharness.SettingsField
import com.arsvie.pocketharness.ShellInfo
import com.arsvie.pocketharness.ui.theme.PhApprovalCard
import com.arsvie.pocketharness.ui.theme.PhBar
import com.arsvie.pocketharness.ui.theme.PhCard
import com.arsvie.pocketharness.ui.theme.PhChip
import com.arsvie.pocketharness.ui.theme.PhComposer
import com.arsvie.pocketharness.ui.theme.PhEmptyState
import com.arsvie.pocketharness.ui.theme.PhIconAction
import com.arsvie.pocketharness.ui.theme.PhLook
import com.arsvie.pocketharness.ui.theme.PhMessage
import com.arsvie.pocketharness.ui.theme.PhMessageBlock
import com.arsvie.pocketharness.ui.theme.PhRole
import com.arsvie.pocketharness.ui.theme.PhSectionHeader
import com.arsvie.pocketharness.ui.theme.PhStatusSquare
import com.arsvie.pocketharness.ui.theme.PhStatusTag
import com.arsvie.pocketharness.ui.theme.PhTheme
import com.arsvie.pocketharness.ui.theme.PhThemes
import com.arsvie.pocketharness.ui.theme.PhThinkingBlock
import com.arsvie.pocketharness.ui.theme.PhToolCard
import com.arsvie.pocketharness.ui.theme.PhToolGroup
import com.arsvie.pocketharness.ui.theme.LocalPhTheme
import ph.policy.ExecutionMode
import ph.ui.Block
import ph.ui.OpenThread
import ph.ui.SettingsState
import ph.ui.ThreadRow

/**
 * The UI-lab screens, rendered entirely through the themed primitives in `ui/theme`. One codebase,
 * four looks (see PhThemes). Values come verbatim from the frozen `ph.ui` types; no logic here.
 * Naming is the harness convention: sessions, turns, tool calls, reasoning, approvals.
 */

// ---------------------------------------------------------------- sessions list

@Composable
fun SessionsScreen(
    sessions: List<ThreadRow>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        PhBar(
            title = "Sessions",
            subtitle = if (sessions.isEmpty()) {
                null
            } else {
                "${sessions.size} session${if (sessions.size == 1) "" else "s"}"
            },
            actions = {
                PhIconAction(Icons.Default.Add, "New session", onNew)
                PhIconAction(Icons.Default.Settings, "Settings", onOpenSettings)
            },
        )
        Box(modifier = Modifier.weight(1f)) {
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PhEmptyState(
                        "No sessions yet",
                        "Tap + to start the first one.\nMessages, tool calls and reasoning all live here.",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sessions, key = { it.id }) { row -> SessionRow(row, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(row: ThreadRow, onOpen: (String) -> Unit) {
    val t = LocalPhTheme.current
    val c = t.colors
    PhCard(onClick = { onOpen(row.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title,
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    row.subtitle,
                    color = c.textDim,
                    fontSize = t.type.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(relativeTime(row.updatedAt), color = c.textFaint, fontSize = t.type.meta)
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis).toString()

// ---------------------------------------------------------------- session view

@Composable
fun SessionScreen(
    open: OpenThread?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDecideApproval: (Boolean) -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        PhBar(
            title = open?.title ?: "Session",
            subtitle = open?.statusLine,
            onBack = onBack,
            actions = {
                if (open != null) {
                    val yolo = open.mode == ExecutionMode.YOLO
                    Text(
                        text = open.mode.name,
                        color = if (yolo) c.warn else c.textDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(t.shape.chip))
                            .background(if (yolo) c.warnTint else c.surfaceAlt)
                            .border(
                                1.dp,
                                if (yolo) c.warn.copy(alpha = 0.5f) else c.hairline,
                                RoundedCornerShape(t.shape.chip),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            },
        )

        if (open == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PhEmptyState("No session open", "Pick one from the list, or start a new one.")
            }
            return
        }

        val approval = open.pendingApproval
        val rows = remember(open.blocks, t.id) { buildRows(open.blocks, t.id == PhLook.STUDIO) }
        // 4chan-style post numbers count posts only (user/assistant text), not reasoning/tool rows.
        val postNos = remember(rows) {
            var n = 0
            rows.map { r ->
                if (r is SessionRowItem.Msg && (r.block is Block.UserText || r.block is Block.AssistantText)) {
                    n += 1
                    "No.$n"
                } else {
                    null
                }
            }
        }
        val itemCount = rows.size + (if (approval != null) 1 else 0)
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
                if (approval != null) PhStatusTag("Waiting for you", c.warn) else PhStatusTag("Running…", c.run)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rows.isEmpty() && approval == null) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhEmptyState("Fresh session", "Send a message to start the turn.")
                    }
                }
            }
            itemsIndexed(rows, key = { i, _ -> "row-$i" }) { i, row ->
                when (row) {
                    is SessionRowItem.Msg -> BlockBody(
                        row.block,
                        postMeta = if (t.message == PhMessage.POST) postNos[i] else null,
                    )

                    is SessionRowItem.Tools -> PhToolGroup(row.calls)
                }
            }
            if (approval != null) {
                item(key = "approval") { PhApprovalCard(approval, onDecideApproval) }
            }
        }

        PhComposer(running = open.running, onSend = onSend, onStop = onStop)
    }
}

@Composable
private fun BlockBody(block: Block, postMeta: String?) {
    when (block) {
        is Block.UserText -> PhMessageBlock(PhRole.USER, block.text, block.queued, postMeta)
        is Block.AssistantText -> PhMessageBlock(PhRole.ASSISTANT, block.text, meta = postMeta)
        is Block.Thinking -> PhThinkingBlock(block.text)
        is Block.ToolCall -> PhToolCard(block)
    }
}

private sealed interface SessionRowItem {
    data class Msg(val block: Block) : SessionRowItem
    data class Tools(val calls: List<Block.ToolCall>) : SessionRowItem
}

/** Studio folds runs of consecutive tool calls into one "N tool calls" card; others keep 1:1. */
private fun buildRows(blocks: List<Block>, groupTools: Boolean): List<SessionRowItem> {
    if (!groupTools) return blocks.map { SessionRowItem.Msg(it) }
    val out = mutableListOf<SessionRowItem>()
    var i = 0
    while (i < blocks.size) {
        val b = blocks[i]
        if (b is Block.ToolCall) {
            var j = i
            while (j < blocks.size && blocks[j] is Block.ToolCall) j++
            val calls = blocks.subList(i, j).filterIsInstance<Block.ToolCall>()
            if (calls.size >= 2) out += SessionRowItem.Tools(calls) else out += SessionRowItem.Msg(calls.first())
            i = j
        } else {
            out += SessionRowItem.Msg(b)
            i++
        }
    }
    return out
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(
    settings: SettingsState,
    shell: ShellInfo?,
    currentTheme: PhTheme,
    onThemeSelected: (PhLook) -> Unit,
    onBack: () -> Unit,
    onModeChange: (ExecutionMode) -> Unit,
    onEdit: (SettingsField, String) -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    var editing by remember { mutableStateOf<SettingsField?>(null) }
    var pickingTheme by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        PhBar(title = "Settings", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "agent") {
                Column {
                    PhSectionHeader("Agent")
                    Spacer(Modifier.height(8.dp))
                    PhCard {
                        SettingRow(
                            label = "Base URL",
                            value = settings.baseUrl.ifBlank { "not set" },
                            mono = true,
                        ) { editing = SettingsField.BASE_URL }
                        HorizontalDivider(color = c.hairline)
                        SettingRow(
                            label = "Model",
                            value = settings.model.ifBlank { "not set" },
                            mono = true,
                        ) { editing = SettingsField.MODEL }
                        Spacer(Modifier.height(14.dp))
                        Text("Reasoning effort", color = c.text, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            settings.reasoningEfforts.forEach { effort ->
                                PhChip(effort, selected = effort == settings.reasoningEffort) {
                                    onEdit(SettingsField.REASONING_EFFORT, effort)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
            }
            item(key = "execution") {
                Column {
                    PhSectionHeader("Execution")
                    Spacer(Modifier.height(8.dp))
                    PhCard {
                        Text("Approval mode", color = c.text, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Controls whether tool calls need your approval.",
                            color = c.textDim,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        ModeOption(
                            title = "DEFAULT",
                            subtitle = "Per-folder trust · approvals on untrusted folders",
                            selected = settings.mode == ExecutionMode.DEFAULT,
                            accent = c.accent,
                            onClick = { onModeChange(ExecutionMode.DEFAULT) },
                        )
                        Spacer(Modifier.height(8.dp))
                        ModeOption(
                            title = "YOLO",
                            subtitle = "Runs without approval · the policy floor still applies",
                            selected = settings.mode == ExecutionMode.YOLO,
                            accent = c.warn,
                            onClick = { onModeChange(ExecutionMode.YOLO) },
                        )
                    }
                }
            }
            item(key = "appearance") {
                Column {
                    PhSectionHeader("Appearance")
                    Spacer(Modifier.height(8.dp))
                    PhCard {
                        SettingRow(
                            label = "Theme",
                            value = currentTheme.name,
                        ) { pickingTheme = true }
                        Text(
                            currentTheme.blurb,
                            color = c.textFaint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            item(key = "security") {
                Column {
                    PhSectionHeader("Security")
                    Spacer(Modifier.height(8.dp))
                    PhCard {
                        SettingRow(
                            label = "API key",
                            value = if (settings.hasApiKey) {
                                "Stored in the Android Keystore"
                            } else {
                                "Not set — tap to add"
                            },
                            chip = if (settings.hasApiKey) c.ok else c.warn,
                        ) { editing = SettingsField.API_KEY }
                    }
                }
            }
            item(key = "diagnostics") {
                Column {
                    PhSectionHeader("Diagnostics")
                    Spacer(Modifier.height(8.dp))
                    PhCard {
                        ShellRow(shell)
                        HorizontalDivider(color = c.hairline)
                        SettingRow(label = "App version", value = BuildConfig.VERSION_NAME)
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
            containerColor = c.surface,
            shape = RoundedCornerShape(t.shape.card),
            title = { Text(titleOf(field), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    if (field == SettingsField.API_KEY) {
                        Text(
                            "Never logged; stored encrypted in the Android Keystore.",
                            color = c.textDim,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            cursorColor = c.accent,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(field, draft)
                    editing = null
                }) { Text("Save", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = c.textDim) } },
        )
    }

    if (pickingTheme) {
        AlertDialog(
            onDismissRequest = { pickingTheme = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(t.shape.card),
            title = { Text("Theme", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    PhThemes.ALL.forEach { option ->
                        ThemeOptionRow(option, selected = option.id == currentTheme.id) {
                            onThemeSelected(option.id)
                            pickingTheme = false
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingTheme = false }) { Text("Close", color = c.textDim) }
            },
        )
    }
}

@Composable
private fun ThemeOptionRow(theme: PhTheme, selected: Boolean, onClick: () -> Unit) {
    val c = LocalPhTheme.current.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.accentTint else Color.Transparent, shape)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.6f) else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(theme.colors.bg)
                .border(1.dp, theme.colors.border, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(3.dp))
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(theme.colors.accent))
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(theme.colors.surface)
                .border(1.dp, theme.colors.border, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(theme.blurb, color = c.textDim, fontSize = 12.sp)
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ShellRow(shell: ShellInfo?) {
    val c = LocalPhTheme.current.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        PhStatusSquare(if (shell != null) c.ok else c.warn)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Shell", color = c.text, fontSize = 14.sp)
            Text(shell?.title ?: "checking…", color = c.textDim, fontSize = 13.sp)
            if (shell != null) {
                Text(
                    shell.detail,
                    color = c.textFaint,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    val shape = RoundedCornerShape(t.shape.small)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, shape)
            .border(1.dp, if (selected) accent.copy(alpha = 0.55f) else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (selected) accent else c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = c.textDim, fontSize = 12.sp)
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    mono: Boolean = false,
    chip: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.shape.small))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = c.text, fontSize = 14.sp)
            Text(
                value,
                color = c.textDim,
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chip != null) {
            Spacer(Modifier.width(8.dp))
            PhStatusSquare(chip)
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun titleOf(field: SettingsField): String = when (field) {
    SettingsField.BASE_URL -> "Base URL"
    SettingsField.MODEL -> "Model"
    SettingsField.REASONING_EFFORT -> "Reasoning effort (blank = omit)"
    SettingsField.API_KEY -> "API key"
}
