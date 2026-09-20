package com.arsvie.pocketharness

import android.app.Notification
import android.app.Notification.Action
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log

/**
 * ADR-005 §5: a foreground service owns the active turn, so backgrounding the app (Home, screen
 * off, the launcher coming to front) does not let Android freeze and kill the process mid-turn.
 *
 * Scope is deliberately small. The service holds *no* turn state and does no work of its own: the
 * turn runs in [AppViewModel]'s coroutine on the app's single-thread dispatcher, and this service's
 * only job is to keep the process foreground-important for as long as that turn is alive. Its
 * lifetime is therefore the turn's lifetime — [start] on turn start, [stop] on turn end — which is
 * also what makes "exactly one active turn at a time" true by construction: there is one
 * notification id, one service instance, and [AppViewModel] cancels any previous run job before
 * starting another.
 *
 * The notification is honest and minimal: the app label and a fixed string from `strings.xml`.
 * No session id, no command, no model output, no transcript content, no secrets — plus, since
 * BACKLOG B-14 E1, one **Stop** action. Stop is the one thing this service does with what it
 * receives: it forwards the action to the process-scoped [TurnControl] (installed by
 * [AppViewModel]) and does nothing else with it, so the "no turn state, no work of its own" rule
 * still holds. That forwarding is also why a Stop from the lock screen aborts the turn without the
 * notification having to know anything about turns.
 *
 * The service never posts anything else. The turn-finished ping is a plain `NotificationManager`
 * post on id 2 ([TurnNotifications]) — not a lifecycle event here, so it cannot resurrect the
 * service or perturb the handshake below (B-14, E2).
 *
 * ## Start/stop ordering (BACKLOG B-1)
 *
 * A turn can fail fast — a refused connection fails in milliseconds — and its `finally` then calls
 * [stop] while this service's `onStartCommand` may not have run yet. Issuing `stopService` before
 * `startForeground` does not cancel the `startForegroundService` obligation: the service record
 * still owes the call, the system times out, and the app dies with
 * `ForegroundServiceDidNotStartInTimeException` (reproduced on the emulator, API 36).
 *
 * The protocol below makes the ordering safe by construction:
 *
 * - [stop] never issues `stopService` until this generation of the service has reached
 *   `startForeground`; if it has not, the stop is parked in [wanted] and honoured by
 *   `onStartCommand` with `stopSelf()` the instant the obligation is met.
 * - [wanted] counts live turns that want the service up so that a fast-failing turn's stop cannot
 *   take down the service of the turn that already superseded it.
 *
 * Both callers live in this process but on different threads (lifecycle callbacks on main, [stop]
 * from the app's single-thread executor), so every transition happens under [lock].
 */
class TurnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // B-14 E1: the notification's Stop action. It is forwarded to the process-scoped control and
        // that is the whole handler — this service still holds no turn state and does no work of its
        // own. Note what this path does *not* do: no startForeground, no touch of
        // wanted/foregroundReached (BACKLOG B-1). A stop is not a service generation, so it cannot
        // create the obligation the protocol exists to keep track of.
        if (intent?.action == ACTION_STOP) {
            if (!TurnControl.stop()) Log.w(TAG, "stop action with no turn control installed")
            return START_NOT_STICKY
        }
        // Must happen within a few seconds of startForegroundService, or the system kills the app.
        startForeground(NOTIFICATION_ID, buildNotification())
        // A stop that raced this start (a turn that failed fast) is honoured only now, never before
        // startForeground: stopping a service that still owes startForeground crashes the app on
        // Android 12+ (BACKLOG B-1).
        if (markForegroundReached()) stopSelf()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = getString(R.string.turn_channel_id)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.turn_channel_name),
                    NotificationManager.IMPORTANCE_LOW, // no sound, no heads-up: a status artifact
                ).apply {
                    description = getString(R.string.turn_channel_description)
                    setShowBadge(false)
                },
            )
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_turn)
            .setContentTitle(getString(R.string.turn_notification_title))
            .setContentText(getString(R.string.turn_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // B-14 E1: the one affordance. Same icon as the status: this is still the turn.
            .addAction(
                Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_turn),
                    getString(R.string.turn_action_stop),
                    stopAction(),
                ).build(),
            )
            .build()
    }

    /**
     * `getService`, not `getActivity`: the action has to reach the process's turn whatever screen is
     * up — including a locked one. The service it names is already running (this notification only
     * exists while it is foreground), so delivery starts nothing and the B-1 handshake is untouched.
     */
    private fun stopAction(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, TurnService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** ADR-005 §5: one turn at a time, so one fixed notification id. */
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PocketHarness"

        /**
         * B-14 E1: what the notification's Stop action carries. Private: the only sender is the
         * action's own [PendingIntent], and nothing outside this service may pretend to be it.
         */
        private const val ACTION_STOP = "com.arsvie.pocketharness.action.TURN_STOP"

        /** The action's request code within this app (the ping has its own, id 2's). */
        private const val REQUEST_STOP = 1

        /** Guards [wanted] and [foregroundReached]. See the class comment (B-1). */
        private val lock = Any()

        /**
         * Live turns that want the service up. 0 or 1 in practice; a count (rather than a flag)
         * keeps [start]/[stop] pairing correct when a fast-failing turn's stop overlaps the next
         * turn's start — a stop must never take down the service of a turn that superseded it.
         */
        private var wanted = 0

        /** True once this generation's `onStartCommand` has called `startForeground`. */
        private var foregroundReached = false

        /** Bring the process up as a foreground service for the duration of a turn. */
        fun start(context: Context) {
            synchronized(lock) {
                wanted += 1
                // The new turn needs a fresh obligation: only *this* generation reaching
                // startForeground may unlock `stopService`.
                foregroundReached = false
            }
            val intent = Intent(context, TurnService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "turn service did not start: " + it.javaClass.simpleName) }
        }

        /**
         * Turn is over (completed, aborted or failed): drop the notification and the service.
         *
         * When the service has not reached `startForeground` yet, the stop is parked instead of
         * issued: the service stops itself the instant it becomes foreground. Calling `stopService`
         * sooner would leave the `startForegroundService` obligation unfulfilled (BACKLOG B-1).
         */
        fun stop(context: Context) {
            val stopNow = synchronized(lock) {
                wanted = (wanted - 1).coerceAtLeast(0)
                wanted == 0 && foregroundReached
            }
            if (stopNow) {
                runCatching { context.stopService(Intent(context, TurnService::class.java)) }
            }
        }

        /**
         * Called on the main thread right after `startForeground`. Returns true when a parked stop
         * — a [stop] that arrived before this point — must now be honoured with `stopSelf()`.
         */
        private fun markForegroundReached(): Boolean = synchronized(lock) {
            foregroundReached = true
            wanted == 0
        }
    }
}
