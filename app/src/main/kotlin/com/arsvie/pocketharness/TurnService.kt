package com.arsvie.pocketharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
 * No session id, no command, no model output, no transcript content, no secrets.
 */
class TurnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of startForegroundService, or the system kills the app.
        startForeground(NOTIFICATION_ID, buildNotification())
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
            .build()
    }

    companion object {
        /** ADR-005 §5: one turn at a time, so one fixed notification id. */
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PocketHarness"

        /** Bring the process up as a foreground service for the duration of a turn. */
        fun start(context: Context) {
            val intent = Intent(context, TurnService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "turn service did not start: " + it.javaClass.simpleName) }
        }

        /** Turn is over (completed, aborted or failed): drop the notification and the service. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnService::class.java)) }
        }
    }
}
