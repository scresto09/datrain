package com.scresto.datrain.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * In a sparing data mode, what was put off on mobile data is caught up as soon as the phone is on Wi-Fi,
 * rather than at the next regular check: Android starts this once a network not charged by volume is there.
 */
class WifiCatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The manifest is tiny: read at once, and timetables waiting for Wi-Fi are downloaded.
        TimetableRepository.get(applicationContext).refresh()
        FavoriteWatchWorker.check(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "rattrapage-wifi"

        /** Once is enough: a request already waiting for Wi-Fi is kept. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<WifiCatchUpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
