package com.arsvie.pocketharness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arsvie.pocketharness.SettingsField
import ph.policy.ExecutionMode
import ph.ui.ApprovalPrompt
import ph.ui.Block
import ph.ui.OpenThread
import ph.ui.SettingsState
import ph.ui.ThreadRow

/**
 * 2010-era Android settings chrome — grey gradient title bar, hairline row dividers, small sans
 * text, tall preference rows. Values verbatim from the frozen `ph.ui` types; no logic here.
 */
object Chrome {
    val BarTop = Color(0xFF6E6E6E)
    val BarBottom = Color(0xFF3A3A3A)
    val Divider = Color(0xFFBFBFBF)
    val RowPress = Color(0xFFE8E8E8)
    val Page = Color(0xFFF2F2F2)
    val RowBg = Color(0xFFFFFFFF)
    val Sub = Color(0xFF6B6B6B)
    val Accent = Color(0xFF4A6E8A)
    val Error = Color(0xFF8A2020)

    val TitleBarHeight = 48.dp
    val RowHeight = 56.dp
    val Hairline = 1.dp
}

@Composable
fun TitleBar(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Chrome.TitleBarHeight)
            .background(Brush.verticalGradient(listOf(Chrome.BarTop, Chrome.BarBottom))),
    ) {
        Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = Color(0xFFD8D8D8), fontSize = 10.sp)
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(Chrome.Hairline).background(Chrome.Divider))
}

@Composable
fun TabStrip(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().background(Color(0xFFDDDDDD))) {
        tabs.forEachIndexed { index, tab ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (on) Chrome.Page else Color(0xFFCCCCCC))
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = Color(0xFF222222),
                )
            }
        }
    }
}

/** A settings-style preference row: tall, white, hairline underneath, label left / control right. */
@Composable
fun PrefRow(label: String, value: String?, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Chrome.RowHeight)
            .background(Chrome.RowBg)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 14.sp, color = Color(0xFF111111))
            if (value != null) Text(text = value, fontSize = 11.sp, color = Chrome.Sub)
        }
        if (trailing != null) trailing()
    }
    Hairline()
}

@Composable
fun CheckRow(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Chrome.RowHeight)
            .background(Chrome.RowBg)
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 14.sp, color = Color(0xFF111111))
            if (subtitle != null) Text(text = subtitle, fontSize = 11.sp, color = Chrome.Sub)
        }
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
    Hairline()
}

// ---------------------------------------------------------------- thread list

@Composable
fun ThreadListScreen(threads: List<ThreadRow>, onOpen: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Chrome.Page)) {
        threads.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Chrome.RowBg)
                    .clickable { onOpen(row.id) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.title, fontSize = 15.sp, color = Color(0xFF111111))
                    Text(text = row.subtitle, fontSize = 11.sp, color = Chrome.Sub)
                    Text(text = "id ${row.id} · updated ${row.updatedAt}", fontSize = 10.sp, color = Chrome.Sub)
                }
                Text(text = "›", fontSize = 22.sp, color = Chrome.Sub)
            }
            if (index != threads.lastIndex) Hairline()
        }
    }
}

// ---------------------------------------------------------------- thread view

@Composable
fun ThreadViewScreen(open: OpenThread) {
    Column(modifier = Modifier.fillMaxSize().background(Chrome.Page).verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().background(Chrome.RowBg).padding(12.dp)) {
            Text(text = open.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
            Text(
                text = "mode " + open.mode.name + (if (open.running) " · running" else " · idle"),
                fontSize = 11.sp,
                color = Chrome.Accent,
            )
            open.statusLine?.let { Text(text = it, fontSize = 10.sp, color = Chrome.Sub) }
        }
        Hairline()
        open.blocks.forEach { block ->
            when (block) {
                is Block.UserText -> BlockBox("you" + if (block.queued) " (queued)" else "", block.text, Color(0xFFEFEFEF))
                is Block.AssistantText -> BlockBox("assistant", block.text, Chrome.RowBg)
                is Block.Thinking -> BlockBox("thinking", block.text, Color(0xFFF7F3E3))
                is Block.ToolCall -> BlockBox(
                    label = "tool · " + block.name + " (" + block.callId + ")",
                    text = block.summary + "\n" + (block.output ?: "<no output yet>"),
                    bg = Chrome.RowBg,
                    error = block.isError,
                    expanded = block.expandedByDefault,
                )
            }
            Hairline()
        }
        open.pendingApproval?.let { prompt ->
            BlockBox("approval needed in " + prompt.cwd, prompt.command, Color(0xFFFDECEC), error = true, expanded = true)
        }
    }
}

@Composable
private fun BlockBox(label: String, text: String, bg: Color, error: Boolean = false, expanded: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().background(bg).padding(12.dp)) {
        Text(
            text = label + if (expanded) "" else "",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (error) Chrome.Error else Chrome.Accent,
        )
        Text(text = text, fontSize = 13.sp, color = Color(0xFF111111))
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(
    settings: SettingsState,
    onModeChange: (ExecutionMode) -> Unit,
    onEdit: (SettingsField, String) -> Unit,
) {
    var editing by remember { mutableStateOf<SettingsField?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(Chrome.Page).verticalScroll(rememberScrollState())) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "POLICY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Chrome.Sub)
        }
        CheckRow(
            label = "Execution mode",
            subtitle = if (settings.mode == ExecutionMode.DEFAULT) {
                "DEFAULT — per-folder trust, approvals on writes"
            } else {
                "YOLO — run without approval (policy floor still applies)"
            },
            checked = settings.mode == ExecutionMode.YOLO,
            onChange = { yolo -> onModeChange(if (yolo) ExecutionMode.YOLO else ExecutionMode.DEFAULT) },
        )

        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "MODEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Chrome.Sub)
        }
        PrefRow(label = "Base URL", value = settings.baseUrl, onClick = { editing = SettingsField.BASE_URL })
        PrefRow(label = "Model", value = settings.model, onClick = { editing = SettingsField.MODEL })
        PrefRow(
            label = "Reasoning effort",
            value = settings.reasoningEffort ?: "none",
            onClick = { editing = SettingsField.REASONING_EFFORT },
        )
        PrefRow(
            label = "Reasoning efforts",
            value = settings.reasoningEfforts.joinToString(", "),
        )

        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "CREDENTIALS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Chrome.Sub)
        }
        PrefRow(
            label = "API key",
            value = if (settings.hasApiKey) "stored in the Android Keystore" else "not set — tap to add",
            onClick = { editing = SettingsField.API_KEY },
        )

        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "BATTERY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Chrome.Sub)
        }
        CheckRow(
            label = "Battery optimisation disclaimer",
            subtitle = if (settings.batteryDisclaimerAcknowledged) "acknowledged" else "not acknowledged",
            checked = settings.batteryDisclaimerAcknowledged,
            onChange = { },
        )
        Spacer(modifier = Modifier.height(24.dp))
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
            title = { Text(text = titleOf(field), fontSize = 15.sp) },
            text = {
                Column {
                    if (field == SettingsField.API_KEY) {
                        Text(text = "Never logged; stored encrypted in the Android Keystore.", fontSize = 11.sp, color = Chrome.Sub)
                    }
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
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

private fun titleOf(field: SettingsField): String = when (field) {
    SettingsField.BASE_URL -> "Base URL"
    SettingsField.MODEL -> "Model"
    SettingsField.REASONING_EFFORT -> "Reasoning effort (blank = omit)"
    SettingsField.API_KEY -> "API key"
}

// ---------------------------------------------------------------- first-run approval

@Composable
fun ApprovalDialog(prompt: ApprovalPrompt, onDecide: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecide(false) },
        title = { Text(text = "Allow command execution in " + prompt.cwd + "?") },
        text = { Text(text = prompt.command, fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = { onDecide(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onDecide(false) }) { Text("Deny") } },
    )
}

// ---------------------------------------------------------------- composer (Send / Stop)

@Composable
fun Composer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().background(Chrome.RowBg)) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = if (running) "steer the running turn…" else "message", fontSize = 13.sp) },
                singleLine = false,
                maxLines = 3,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                onSend(draft)
                draft = ""
            }) { Text("Send") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onStop, enabled = running) { Text("Stop") }
        }
    }
}

// ---------------------------------------------------------------- new thread

@Composable
fun NewThreadRow(onNew: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFFDDDDDD))
            .clickable { onNew() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "+ New thread", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222))
    }
    Hairline()
}

@Composable
fun ScreenScaffold(title: String, subtitle: String, tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, body: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(title, subtitle)
        TabStrip(tabs = tabs, selected = selected, onSelect = onSelect)
        Hairline()
        Box(modifier = Modifier.weight(1f)) { body() }
    }
}
