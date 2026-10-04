package com.meterreading.reader.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meterreading.reader.MainActivity
import com.meterreading.reader.R
import java.time.Instant

/** The "Signal is back — Send now / Later" notification shown while the app is closed (FR-020.4). */
object SyncNotification {
    private const val CHANNEL = "sync"
    private const val ID = 4201

    fun show(context: Context, readings: Int, photos: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return // no permission: the app asks when it is opened
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.sync_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mark)
            .setContentTitle(context.getString(R.string.sync_title))
            .setContentText(context.getString(R.string.sync_text, readings, photos))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.sync_now), action(context, SyncActionReceiver.SEND))
            .addAction(0, context.getString(R.string.sync_later), action(context, SyncActionReceiver.LATER))
            .build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    private fun action(context: Context, what: String): PendingIntent = PendingIntent.getBroadcast(
        context, what.hashCode(), Intent(context, SyncActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Handles the notification's two buttons. */
class SyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncNotification.cancel(context)
        when (intent.action) {
            SEND -> UploadWorker.sendNow(context)
            LATER -> {
                AppServices.init(context)
                AppServices.snooze(Instant.now())
                UploadWorker.scheduleCheck(context, com.meterreading.reader.data.SyncPrompt.SNOOZE)
            }
        }
    }

    companion object {
        const val SEND = "com.meterreading.reader.SYNC_NOW"
        const val LATER = "com.meterreading.reader.SYNC_LATER"
    }
}
