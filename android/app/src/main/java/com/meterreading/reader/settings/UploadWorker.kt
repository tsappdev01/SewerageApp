package com.meterreading.reader.settings

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meterreading.reader.data.AppGraph
import java.util.concurrent.TimeUnit

/**
 * FR-020.4: sends readings and photos waiting on the phone as soon as there is a connection, even
 * when the app is closed. The queue is read from its encrypted file, so nothing depends on the
 * app having stayed open.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppServices.init(applicationContext)
        val repo = AppGraph.repository
        repo.sendQueued()
        // Still waiting (no signal again, server busy, or sign-in needed): try again later.
        return if (repo.hasWaiting() && !repo.signInNeeded.value) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "send-waiting-readings"

        /** Asks Android to run the upload once a network is available. Safe to call often. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
