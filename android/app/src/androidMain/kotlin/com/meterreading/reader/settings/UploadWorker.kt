package com.meterreading.reader.settings

import kotlinx.datetime.Clock
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.SyncPrompt
import com.meterreading.reader.platform.AndroidPlatform
import com.meterreading.reader.platform.between
import com.meterreading.reader.platform.isBefore
import kotlinx.datetime.Instant
import kotlin.time.Duration
import java.util.concurrent.TimeUnit

/**
 * FR-020.4. "Check" runs once the phone has a network while readings wait: with the app closed it
 * shows the "Signal is back — Send now / Later" notification (in the app, a dialog asks instead).
 * "Send" runs when the reader taps Send now on that notification. Nothing is sent without asking.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AndroidPlatform.start(applicationContext)
        val repo = AppGraph.repository
        if (inputData.getBoolean(KEY_SEND, false)) {
            AppGraph.sendAll()
            SyncNotification.cancel(applicationContext)
            // Signal dropped again: the reader will be asked again when it is back.
            if (AppGraph.hasWaiting() && !repo.signInNeeded.value) scheduleCheck(applicationContext, SyncPrompt.RETRY)
            return Result.success()
        }
        if (!AppGraph.hasWaiting()) return Result.success()
        val snoozedUntil = AppServices.snoozedUntil
        val now = Clock.System.now()
        if (snoozedUntil != null && now.isBefore(snoozedUntil)) {
            scheduleCheck(applicationContext, Duration.between(now, snoozedUntil))
        } else if (!AppServices.inForeground) {
            val inspections = AppGraph.inspections
            SyncNotification.show(
                applicationContext,
                repo.readings.value.count { it.state == com.meterreading.reader.data.ReadingState.QUEUED } + (inspections?.waitingVisits?.value ?: 0),
                repo.photosWaiting.value + (inspections?.waitingPhotos?.value ?: 0),
            )
        }
        return Result.success()
    }

    companion object {
        private const val CHECK = "check-waiting-readings"
        private const val SEND = "send-waiting-readings"
        private const val KEY_SEND = "send"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Runs the check once a network is there (after [delay]). Safe to call often. */
        fun scheduleCheck(context: Context, delay: Duration = Duration.ZERO) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(network)
                .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHECK, if (delay == Duration.ZERO) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }

        /** The reader tapped Send now on the notification. */
        fun sendNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(KEY_SEND to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SEND, ExistingWorkPolicy.KEEP, request)
        }
    }
}
