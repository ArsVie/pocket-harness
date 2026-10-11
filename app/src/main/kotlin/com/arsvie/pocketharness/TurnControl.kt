package com.arsvie.pocketharness

/**
 * B-14 E1: the seam between the notification's Stop action and the turn it belongs to.
 *
 * The notification is posted by [TurnService], which holds no turn state and does no work of its
 * own; the turn lives in [AppViewModel]'s coroutine. The action and the turn therefore meet here
 * and nowhere else: the ViewModel installs its stop on creation (process-scoped — the newest
 * install wins, the same rule the app already applies to "one active turn at a time"), and the
 * service forwards whatever arrives from the notification to whatever is installed.
 *
 * Nothing here keeps a turn (or a service) alive and nothing here starts anything: with no action
 * installed [stop] is a no-op that reports itself, which is what a stale notification action — a
 * process that was killed and restarted — deserves.
 */
object TurnControl {

    private val lock = Any()

    private var onStop: (() -> Unit)? = null

    /** Installs the current stop path. Called by [AppViewModel] on creation. */
    fun install(action: () -> Unit) {
        synchronized(lock) { onStop = action }
    }

    /**
     * Forwards a Stop to the installed action. False when nothing is installed — there is no turn
     * to end, and the caller must not invent one.
     */
    fun stop(): Boolean {
        val action = synchronized(lock) { onStop } ?: return false
        action()
        return true
    }
}
