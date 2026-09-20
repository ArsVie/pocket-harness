package com.arsvie.pocketharness.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.arsvie.pocketharness.BuildConfig
import com.arsvie.pocketharness.NEW_SESSION_TITLE
import com.arsvie.pocketharness.R
import com.arsvie.pocketharness.SettingsField
import com.arsvie.pocketharness.ShellInfo
import com.arsvie.pocketharness.platform.BatteryStatus
import com.arsvie.pocketharness.ui.theme.PhApprovalCard
import com.arsvie.pocketharness.ui.theme.PhBar
import com.arsvie.pocketharness.ui.theme.PhButton
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The UI-lab screens, rendered entirely through the themed primitives in `ui/theme`. One codebase,
 * four looks (see PhThemes). Values come verbatim from the frozen `ph.ui` types; no logic here.
 * Naming is the harness convention: sessions, turns, tool calls, reasoning, approvals.
 */

// ---------------------------------------------------------------- sessions list

/**
 * B-21 gesture tuneables — named and commented, never scattered literals. Swipe distances are
 * fractions of the row width, so they hold on any screen the list is rendered on.
 */

/** Fraction of the row width a swipe must cross on release for its action to commit (A2/A3). */
private const val SWIPE_COMMIT_FRACTION = 0.30f

/** Fraction of the row width at which the icon behind the row is fully revealed. */
private const val SWIPE_REVEAL_FRACTION = 0.22f

/** A lifted row grows by this factor; the shadow under it makes the pick-up read (A1). */
private const val DRAG_LIFT_SCALE = 1.03f

/** How far (dp) the lifted row's shadow reaches. */
private val DRAG_LIFT_SHADOW = 8.dp

/** The list's row spacing — the drag math must use the same step the list lays rows out with. */
private val SESSION_ROW_SPACING = 10.dp

/** The revealed action icon: inset from the row edge, and its size. */
private val SWIPE_ICON_INSET = 18.dp
private val SWIPE_ICON_SIZE = 20.dp

@Composable
fun SessionsScreen(
    sessions: List<ThreadRow>,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onPlace: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    var renaming by remember { mutableStateOf<ThreadRow?>(null) }
    var pendingDelete by remember { mutableStateOf<ThreadRow?>(null) }
    // B-21 A1: at most one row is lifted at a time; while it is, the others stop taking gestures.
    var draggingId by remember { mutableStateOf<String?>(null) }
    val blocks = remember(sessions) { pinBlocks(sessions) }
    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        PhBar(
            title = "Sessions",
            // B2: no count before the first scan has landed — the list is "not read yet", not empty.
            subtitle = if (loading || sessions.isEmpty()) {
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
            when {
                loading -> SessionsLoading()
                sessions.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PhEmptyState(
                        "No sessions yet",
                        "Tap + to start the first one.\nMessages, tool calls and reasoning all live here.",
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(SESSION_ROW_SPACING),
                ) {
                    itemsIndexed(sessions, key = { _, row -> row.id }) { index, row ->
                        SessionRow(
                            row = row,
                            index = index,
                            blockRange = blocks[index],
                            // A dragged row keeps its own gestures; every other row stands down.
                            dragBlocked = draggingId != null && draggingId != row.id,
                            onDragStart = { draggingId = row.id },
                            onDragEnd = { if (draggingId == row.id) draggingId = null },
                            onPlace = onPlace,
                            onOpen = onOpen,
                            onRename = { renaming = it },
                            onPin = onPin,
                            onDelete = { pendingDelete = it },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { row ->
        var draft by remember(row.id) { mutableStateOf(row.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(t.shape.card),
            title = {
                Text("Rename session", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
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
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(row.id, draft)
                    renaming = null
                }) { Text("Save", color = c.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel", color = c.textDim) }
            },
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(t.shape.card),
            title = {
                Text("Delete session?", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "\u201C${row.title}\u201D moves to the app's trash. Its sandbox folder goes with it; " +
                        "a custom workspace folder is left untouched.",
                    color = c.textDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row.id)
                    pendingDelete = null
                }) { Text("Delete", color = c.warn) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = c.textDim) }
            },
        )
    }
}

@Composable
private fun SessionRow(
    row: ThreadRow,
    index: Int,
    blockRange: IntRange,
    dragBlocked: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onPlace: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (ThreadRow) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (ThreadRow) -> Unit,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    var menuOpen by remember { mutableStateOf(false) }
    val spacingPx = with(LocalDensity.current) { SESSION_ROW_SPACING.toPx() }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }

    // The detectors below are launched once per pointerInput key and then keep running, so the row's
    // *current* facts are read through state: a value captured at attach time would be the one from
    // before this row was measured, re-pinned or renamed.
    val liveRow = rememberUpdatedState(row)
    val liveBlockRange = rememberUpdatedState(blockRange)

    // A1/A5: hold + drag. The offset follows the finger while the row is lifted; on release the row
    // either lands where it was dropped (`dragLanded`, so it must not also spring) or springs home.
    var dragging by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragLanded by remember { mutableStateOf(false) }

    // A2/A3: swipe right reveals the Star (pin), swipe left the Delete. `swipeLanded` is set when the
    // action moved the row itself (pin/unpin): the row is already in its new place, so it must not
    // glide back across the list.
    var swiping by remember { mutableStateOf(false) }
    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    var swipeLanded by remember { mutableStateOf(false) }

    val settleY by animateFloatAsState(
        targetValue = if (dragging) dragOffsetY else 0f,
        animationSpec = if (dragging) snap() else rowSettleSpring,
        label = "sessionRowDragY",
    )
    val settleX by animateFloatAsState(
        targetValue = if (swiping) swipeOffsetX else 0f,
        animationSpec = if (swiping) snap() else rowSettleSpring,
        label = "sessionRowSwipeX",
    )
    val offsetY = when {
        dragging -> dragOffsetY
        dragLanded -> 0f
        else -> settleY
    }
    val offsetX = when {
        swiping -> swipeOffsetX
        swipeLanded -> 0f
        else -> settleX
    }
    val revealPx = rowWidthPx * SWIPE_REVEAL_FRACTION
    val revealProgress = if (revealPx > 0f) (abs(offsetX) / revealPx).coerceIn(0f, 1f) else 0f

    /** A drop: commit inside the row's own pin-block, spring back for anything else (A1/A5). */
    fun settleDrag() {
        val block = liveBlockRange.value
        val id = liveRow.value.id
        val stride = rowHeightPx + spacingPx
        val raw = stepIndex(offset = dragOffsetY, from = index, stride = stride)
        val target = if (block.isEmpty()) index else raw.coerceIn(block.first, block.last)
        if (stride > 0f && raw == target && target != index) {
            onPlace(id, target)
            dragLanded = true
        } else {
            dragLanded = false
        }
        dragging = false
        dragOffsetY = 0f
        onDragEnd()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                rowHeightPx = it.size.height.toFloat()
                rowWidthPx = it.size.width.toFloat()
            }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = offsetY
                val lift = if (dragging) DRAG_LIFT_SCALE else 1f
                scaleX = lift
                scaleY = lift
                if (dragging) {
                    shape = RoundedCornerShape(t.shape.card)
                    clip = true
                    shadowElevation = DRAG_LIFT_SHADOW.toPx()
                }
            }
            // A1: a hold lifts the row and drags it up/down. This detector loses the arena to the
            // list's scroll before the long press fires and to the swipe below on a sideways flick,
            // so the three gestures resolve by direction instead of fighting over one row.
            .pointerInput(row.id, index, dragBlocked) {
                if (dragBlocked) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        dragLanded = false
                        dragOffsetY = 0f
                        onDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffsetY += amount.y
                    },
                    onDragEnd = { settleDrag() },
                    onDragCancel = {
                        dragLanded = false
                        dragging = false
                        dragOffsetY = 0f
                        onDragEnd()
                    },
                )
            }
            // A2/A3: a horizontal drag reveals one icon behind the row; releasing past the threshold
            // runs the same action the three-dot menu runs, and every path springs the row back.
            .pointerInput(row.id, dragBlocked) {
                if (dragBlocked) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        swiping = true
                        swipeLanded = false
                        swipeOffsetX = 0f
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipeOffsetX = (swipeOffsetX + amount).coerceIn(-rowWidthPx, rowWidthPx)
                    },
                    onDragEnd = {
                        // The threshold is read here, not from the composition that attached this
                        // detector: a row is measured after that, so the captured width would be 0.
                        val commitPx = rowWidthPx * SWIPE_COMMIT_FRACTION
                        val travel = swipeOffsetX
                        if (commitPx > 0f && travel >= commitPx) {
                            // A2: right pins — and on an already-pinned row the same gesture unpins.
                            val current = liveRow.value
                            onPin(current.id, !current.pinned)
                            swipeLanded = true
                        } else if (commitPx > 0f && -travel >= commitPx) {
                            // A3: left deletes through the same confirm dialog as the menu.
                            onDelete(liveRow.value)
                        }
                        swiping = false
                        swipeOffsetX = 0f
                    },
                    onDragCancel = {
                        swiping = false
                        swipeOffsetX = 0f
                    },
                )
            },
    ) {
        // A2/A3: drawn behind the row, so it is revealed as the row slides off it.
        RowSwipeBackdrop(
            revealRight = offsetX > 0f,
            progress = revealProgress,
            pinned = row.pinned,
        )
        PhCard(
            modifier = Modifier.graphicsLayer { translationX = offsetX },
            onClick = { onOpen(row.id) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Pinned",
                        tint = c.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                }
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
                Box {
                    PhIconAction(Icons.Default.MoreVert, "Session actions", { menuOpen = true })
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = c.surface,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename…", color = c.text) },
                            onClick = {
                                menuOpen = false
                                onRename(row)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (row.pinned) "Unpin" else "Pin to top", color = c.text) },
                            onClick = {
                                menuOpen = false
                                onPin(row.id, !row.pinned)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete…", color = c.warn) },
                            onClick = {
                                menuOpen = false
                                onDelete(row)
                            },
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * A2/A3: what the swipe reveals behind a row — the `Star` for the right swipe (pin/unpin) and the
 * `Delete` for the left one, on a tinted backdrop so the icon reads as an action, not as decoration.
 */
@Composable
private fun BoxScope.RowSwipeBackdrop(revealRight: Boolean, progress: Float, pinned: Boolean) {
    val t = LocalPhTheme.current
    val c = t.colors
    val shape = RoundedCornerShape(t.shape.card)
    val tone = if (revealRight) c.accent else c.warn
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(if (revealRight) c.accentTint else c.warnTint, shape),
        contentAlignment = if (revealRight) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = if (revealRight) Icons.Default.Star else Icons.Default.Delete,
            contentDescription = when {
                !revealRight -> "Delete"
                pinned -> "Unpin"
                else -> "Pin"
            },
            tint = tone.copy(alpha = progress),
            modifier = Modifier.padding(horizontal = SWIPE_ICON_INSET).size(SWIPE_ICON_SIZE),
        )
    }
}

/** B2: neutral state while the first session-list scan runs — no count, no "No sessions yet". */
@Composable
private fun SessionsLoading() {
    val c = LocalPhTheme.current.colors
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Loading sessions…", color = c.textDim, fontSize = 13.sp)
    }
}

/**
 * A5: the list indices each row may be dropped into — pinned rows move among pinned rows only and
 * unpinned among unpinned only. It is read from the rows' own `pinned` flag, so it mirrors the
 * grouping the projection renders instead of second-guessing it.
 */
private fun pinBlocks(sessions: List<ThreadRow>): List<IntRange> {
    val unpinnedStart = sessions.count { it.pinned }
    return sessions.map { row ->
        if (row.pinned) 0..(unpinnedStart - 1) else unpinnedStart..sessions.lastIndex
    }
}

/**
 * The list index a dragged row is over: one step per row height + spacing, rounded, so the row swaps
 * when the finger has crossed half a step. Rows are uniform in height (title and subtitle are both
 * `maxLines = 1`), which is what lets one measured stride stand in for every row.
 */
private fun stepIndex(offset: Float, from: Int, stride: Float): Int =
    if (stride <= 0f) from else from + (offset / stride).roundToInt()

/** The spring a released row settles with (swipe back, or the drop that was refused). */
private val rowSettleSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy)

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis).toString()

// ---------------------------------------------------------------- session view

/**
 * B3: an untitled session is projected with its own id as the title
 * (`DefaultThreadProjector.titleOf` falls back to `header.id`), and that id is the app's business,
 * not the owner's — the header shows the same copy the list row shows.
 */
private fun threadTitle(open: OpenThread): String =
    if (open.title == open.id) NEW_SESSION_TITLE else open.title

@Composable
fun SessionScreen(
    open: OpenThread?,
    loading: Boolean,
    battery: BatteryStatus?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDecideApproval: (Boolean) -> Unit,
    onOpenBattery: () -> Boolean,
) {
    val t = LocalPhTheme.current
    val c = t.colors
    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        PhBar(
            title = open?.let { threadTitle(it) } ?: "Session",
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
                if (loading) {
                    // B1: the target session is still being replayed — say so instead of showing
                    // either the previous session or a "pick one from the list" that is not true.
                    Text("Loading session…", color = c.textDim, fontSize = 13.sp)
                } else {
                    PhEmptyState("No session open", "Pick one from the list, or start a new one.")
                }
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

        // B-12: while the app is not battery-exempt, turns can be killed in the background.
        if (battery != null && !battery.exempt) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                BatteryWarningCard(onOpenBattery)
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

/**
 * B-12: shown in the thread while the app is not exempt from battery optimization. The button
 * opens this app's battery page; when no page resolves, the manual path is revealed instead.
 */
@Composable
private fun BatteryWarningCard(onOpenBattery: () -> Boolean) {
    val c = LocalPhTheme.current.colors
    var showManualPath by remember { mutableStateOf(false) }
    PhCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhStatusSquare(c.warn, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Battery optimization is on",
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("Turns may be killed in the background.", color = c.textDim, fontSize = 13.sp)
        if (showManualPath) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Open manually: Settings → Apps → ${stringResource(R.string.app_name)} → Battery → Unrestricted.",
                color = c.textFaint,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "On MIUI: also enable Autostart and lock the app in Recents.",
                color = c.textFaint,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        PhButton("Open battery settings", { if (!onOpenBattery()) showManualPath = true })
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(
    settings: SettingsState,
    shell: ShellInfo?,
    battery: BatteryStatus?,
    currentTheme: PhTheme,
    onThemeSelected: (PhLook) -> Unit,
    onBack: () -> Unit,
    onModeChange: (ExecutionMode) -> Unit,
    onEdit: (SettingsField, String) -> Unit,
    onOpenBattery: () -> Boolean,
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
                        BatteryRow(battery, onOpenBattery)
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
private fun BatteryRow(status: BatteryStatus?, onOpen: () -> Boolean) {
    val c = LocalPhTheme.current.colors
    var showManualPath by remember { mutableStateOf(false) }
    val actionable = status != null && !status.exempt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (actionable) Modifier.clickable { if (!onOpen()) showManualPath = true } else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhStatusSquare(
            when {
                status == null -> c.textFaint
                status.exempt -> c.ok
                else -> c.warn
            },
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Battery optimization", color = c.text, fontSize = 14.sp)
            Text(
                when {
                    status == null -> "checking…"
                    status.exempt -> "Exempt — turns run to completion in the background"
                    else -> "On — turns may be killed in the background"
                },
                color = c.textDim,
                fontSize = 13.sp,
            )
            if (status != null) {
                Text(
                    "Power save " + (if (status.powerSave) "on" else "off") +
                        " · notifications " + (if (status.notificationsAllowed) "allowed" else "denied"),
                    color = c.textFaint,
                    fontSize = 11.sp,
                )
            }
            if (showManualPath) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Settings → Apps → ${stringResource(R.string.app_name)} → Battery → Unrestricted; on MIUI also Autostart + lock in Recents.",
                    color = c.textFaint,
                    fontSize = 11.sp,
                )
            }
        }
        if (actionable) {
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
