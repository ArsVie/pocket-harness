package com.arsvie.pocketharness

/**
 * B-14 E2: whether this process has a visible activity.
 *
 * The turn-finished ping is posted only when the app is **not** in the foreground — in the
 * foreground the thread already shows the outcome and a notification would be noise. Written by
 * [MainActivity]'s `onStart`/`onStop` (main thread) and read by the turn's `finally` block (the
 * app's single-thread dispatcher), hence [Volatile].
 *
 * Deliberately dumb: one flag, no lifecycle ownership, no listeners, no state of its own to
 * outlive the process.
 */
object AppForeground {

    /** True between `onStart` and `onStop` of the app's single activity. */
    @Volatile
    var visible: Boolean = false
}
