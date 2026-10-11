package com.arsvie.pocketharness.ui

import ph.policy.ExecutionMode
import ph.ui.Block
import ph.ui.OpenThread
import ph.ui.SettingsState
import ph.ui.ThreadRow
import ph.ui.UiState

/**
 * Fixture [UiState] for the Wave-0 skeleton screens. No logic: the real projections come from
 * `ph.ui.ThreadProjector` / the settings store in `:core`. This file exists so `:app` can render
 * the three screens against the frozen types before any of that is wired.
 */
object Fixtures {

    val ui: UiState = UiState(
        threads = listOf(
            ThreadRow(
                id = "s-0001",
                title = "Refactor the exec path",
                subtitle = "assistant · 3 tool calls",
                updatedAt = 1_756_000_000_000,
            ),
            ThreadRow(
                id = "s-0002",
                title = "Why is uname slow on this emulator",
                subtitle = "assistant · running",
                updatedAt = 1_755_990_000_000,
                pinned = true,
            ),
            ThreadRow(
                id = "s-0003",
                title = "Summarise userland/PROVENANCE.md",
                subtitle = "idle",
                updatedAt = 1_755_800_000_000,
            ),
        ),
        open = OpenThread(
            id = "s-0001",
            title = "Refactor the exec path",
            running = false,
            statusLine = "turn 2 · 1.4k tokens · deepseek-v4.1-flash",
            pendingApproval = null,
            mode = ExecutionMode.DEFAULT,
            blocks = listOf(
                Block.UserText("Read ShellBinaries and tell me what the app must provide.", queued = false),
                Block.Thinking("Bash is unpacked to files/userland; PATH is the shim dir then /system/bin."),
                Block.AssistantText("The app owns three ports: Shell, ShellBinaries, and SecretStore."),
                Block.ToolCall(
                    callId = "c-1",
                    name = "bash",
                    summary = "ls -la files/userland",
                    output = "bash\n",
                    isError = false,
                    expandedByDefault = false,
                ),
                Block.ToolCall(
                    callId = "c-2",
                    name = "bash",
                    summary = "rm work/doomed.txt",
                    output = "",
                    isError = false,
                    expandedByDefault = false,
                ),
            ),
        ),
        settings = SettingsState(
            mode = ExecutionMode.DEFAULT,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-v4.1-flash",
            reasoningEffort = "medium",
            reasoningEfforts = listOf("low", "medium", "high"),
            hasApiKey = false,
        ),
    )
}
