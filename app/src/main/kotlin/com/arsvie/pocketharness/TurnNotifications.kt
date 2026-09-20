package com.arsvie.pocketharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log

/**
 * The finished ping: a plain `NotificationManager` post on its own notification id.
 *
 * Plain is the point (BACKLOG B-14, E2). The ping is not a service lifecycle event — it does not
 * start, stop or otherwise touch [TurnService], so it has no way to resurrect the service or
 * perturb the `wanted`/`foregroundReached` handshake (B-1). The ongoing "turn running" notification
 * (id 1) stays owned by `startForeground` and dies with the service; this one deliberately outlives
 * the service, because its whole job is to say that a turn ended while the owner was looking
 * somewhere else.
 *
 * Posted by [AppViewModel] from the turn's `finally` block, and only when the app is not visible
 * ([AppForeground]): in the foreground the thread already shows the outcome. Copy is fixed and
 * outcome-aware — no session id, no command, no model output, no secrets, the same rule id 1
 * follows.
 */
object TurnNotifications {

    /**
     * Posts the ping for a turn that just ended with the app off screen. [failed] picks the copy
     * ("Turn failed" / "Turn finished") — the only thing this notification says about the turn.
     */
    fun postFinished(context: Context, failed: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            ensureChannel(context, manager)
            manager.notify(FINISHED_ID, build(context, failed))
        }.onFailure { Log.w(TAG, "turn ping not posted: " + it.javaClass.simpleName) }
    }

    /** Drops a ping that is still in the shade: the next turn supersedes it (B-14 E2). */
    fun cancelFinished(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(FINISHED_ID) }
    }

    /**
     * IMPORTANCE_DEFAULT, not LOW: the ping is a ping — it may make a sound, where the ongoing
     * status (id 1) deliberately may not.
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        val channelId = context.getString(R.string.turn_done_channel_id)
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                context.getString(R.string.turn_done_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.turn_done_channel_description)
            },
        )
    }

    private fun build(context: Context, failed: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, context.getString(R.string.turn_done_channel_id))
            .setSmallIcon(R.drawable.ic_stat_turn)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.getString(
                    if (failed) {
                        R.string.turn_failed_notification_text
                    } else {
                        R.string.turn_finished_notification_text
                    },
                ),
            )
            // Tap → the app, and the ping retires itself (B-14 E2).
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    /** The ongoing notification owns id 1 ([TurnService]); the ping owns id 2. */
    private const val FINISHED_ID = 2
    private const val REQUEST_OPEN = 0
    private const val TAG = "PocketHarness"
}
