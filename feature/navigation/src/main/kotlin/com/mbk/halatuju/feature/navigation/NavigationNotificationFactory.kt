package com.mbk.halatuju.feature.navigation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat

object NavigationNotificationFactory {
    const val CHANNEL_ID = "halatuju_navigation"
    const val NOTIFICATION_ID = 4101

    const val ACTION_END_NAVIGATION = "com.mbk.halatuju.action.END_NAVIGATION"
    const val ACTION_DISMISSED_PROMOTED = "com.mbk.halatuju.action.DISMISSED_PROMOTED_NOTIFICATION"
    const val EXTRA_OPEN_ACTIVE_NAVIGATION = "com.mbk.halatuju.extra.OPEN_ACTIVE_NAVIGATION"

    private const val COLOR_TRAVELED_SEGMENT = 0xFF707E7E.toInt()
    private const val COLOR_REMAINING_SEGMENT = 0xFF0E5A58.toInt()
    private const val COLOR_STOP_POINT = 0xFFF4B400.toInt()

    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance != NotificationManager.IMPORTANCE_MIN) return

        // Live Update notifications must NOT use IMPORTANCE_MIN; use silent IMPORTANCE_DEFAULT
        // because TTS and overspeed chimes handle guidance audio independently.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Turn-by-turn navigation",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Active route maneuver, distance, ETA and trip progress"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(
        context: Context,
        payload: NavigationNotificationPayload,
        requestPromotedOngoing: Boolean = true
    ): Notification {
        ensureNotificationChannel(context)

        val contentIntent = createOpenAppPendingIntent(context)
        val endPendingIntent = createBroadcastPendingIntent(context, ACTION_END_NAVIGATION, 101)
        val deletePendingIntent = createBroadcastPendingIntent(context, ACTION_DISMISSED_PROMOTED, 102)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(drawableForManeuver(payload.icon))
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)

        if (payload.isOngoing) {
            builder
                .setOngoing(true)
                .setAutoCancel(false)
                .setDeleteIntent(deletePendingIntent)
                .setRequestPromotedOngoing(requestPromotedOngoing)
                .setShortCriticalText(payload.shortCriticalText)
                .setStyle(buildProgressStyle(context, payload))

            if (payload.showEndAction) {
                builder.addAction(0, "End", endPendingIntent)
            }
        } else {
            builder
                .setOngoing(false)
                .setAutoCancel(true)
                .setRequestPromotedOngoing(false)
        }

        return builder.build()
    }

    private fun buildProgressStyle(
        context: Context,
        payload: NavigationNotificationPayload
    ): NotificationCompat.ProgressStyle {
        val done = payload.progressPermille.coerceIn(0, 1_000)
        val segments = buildList {
            when {
                done <= 0 -> add(
                    NotificationCompat.ProgressStyle.Segment(1_000)
                        .setId(1)
                        .setColor(COLOR_REMAINING_SEGMENT)
                )
                done >= 1_000 -> add(
                    NotificationCompat.ProgressStyle.Segment(1_000)
                        .setId(1)
                        .setColor(COLOR_TRAVELED_SEGMENT)
                )
                else -> {
                    add(
                        NotificationCompat.ProgressStyle.Segment(done)
                            .setId(1)
                            .setColor(COLOR_TRAVELED_SEGMENT)
                    )
                    add(
                        NotificationCompat.ProgressStyle.Segment(1_000 - done)
                            .setId(2)
                            .setColor(COLOR_REMAINING_SEGMENT)
                    )
                }
            }
        }

        val points = payload.stopProgressMarkersPermille
            .filter { it in 1..999 }
            .mapIndexed { idx, position ->
                NotificationCompat.ProgressStyle.Point(position)
                    .setId(10 + idx)
                    .setColor(COLOR_STOP_POINT)
            }

        return NotificationCompat.ProgressStyle()
            .setProgressSegments(segments)
            .setProgressPoints(points)
            .setProgress(done)
            .setStyledByProgress(false)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_nav_tracker))
    }

    @DrawableRes
    fun drawableForManeuver(icon: NotificationManeuverIcon): Int {
        return when (icon) {
            NotificationManeuverIcon.STRAIGHT -> R.drawable.ic_nav_straight
            NotificationManeuverIcon.TURN_LEFT -> R.drawable.ic_nav_turn_left
            NotificationManeuverIcon.TURN_RIGHT -> R.drawable.ic_nav_turn_right
            NotificationManeuverIcon.SLIGHT_LEFT -> R.drawable.ic_nav_slight_left
            NotificationManeuverIcon.SLIGHT_RIGHT -> R.drawable.ic_nav_slight_right
            NotificationManeuverIcon.SHARP_LEFT -> R.drawable.ic_nav_sharp_left
            NotificationManeuverIcon.SHARP_RIGHT -> R.drawable.ic_nav_sharp_right
            NotificationManeuverIcon.UTURN_LEFT -> R.drawable.ic_nav_uturn_left
            NotificationManeuverIcon.UTURN_RIGHT -> R.drawable.ic_nav_uturn_right
            NotificationManeuverIcon.MERGE -> R.drawable.ic_nav_merge
            NotificationManeuverIcon.ROUNDABOUT -> R.drawable.ic_nav_roundabout
            NotificationManeuverIcon.ARRIVE_STOP -> R.drawable.ic_nav_stop
            NotificationManeuverIcon.ARRIVE_DESTINATION -> R.drawable.ic_nav_arrive
        }
    }

    private fun createOpenAppPendingIntent(context: Context): PendingIntent {
        val launchIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .apply {
                putExtra(EXTRA_OPEN_ACTIVE_NAVIGATION, true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        return PendingIntent.getActivity(
            context,
            100,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createBroadcastPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, NavigationNotificationReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun supportsLiveUpdatePromotions(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 36

    fun isNotificationPromotedOnSystem(notification: Notification): Boolean {
        return if (supportsLiveUpdatePromotions()) {
            NotificationCompat.hasPromotableCharacteristics(notification)
        } else {
            false
        }
    }

    fun postOrUpdateNotification(
        context: Context,
        payload: NavigationNotificationPayload,
        requestPromotedOngoing: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = buildNotification(
            context = context,
            payload = payload,
            requestPromotedOngoing = requestPromotedOngoing
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
