package com.scresto.datrain.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/**
 * Extra checks shortly before each favorite leaves. The regular check runs every 15 minutes at best,
 * and much less often while the phone sleeps: early in the morning, a cancellation could come too late.
 * These alarms go off even then, without any special permission, give or take a few minutes.
 */
object DepartureAlarms {
    /** From two hours ahead, when many decide whether to leave, to the last quarter of an hour. */
    private val MINUTES_BEFORE = longArrayOf(120, 90, 60, 30, 15)
    private const val WORK_NAME = "favoris-depart"

    /**
     * Only the soonest alarm is set: the check it starts sets the next one. One more just after each
     * estimated arrival, so that the widget moves on to the next train without waiting for the regular check.
     */
    fun schedule(context: Context, departures: List<LocalDateTime>, now: LocalDateTime, arrivals: List<LocalDateTime> = emptyList()) {
        val next = (departures.flatMap { departure -> MINUTES_BEFORE.map { departure.minusMinutes(it) } } + arrivals.map { it.plusMinutes(1) })
            .filter { it > now.plusMinutes(1) }
            .minOrNull()
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, DepartureAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (next == null) {
            manager.cancel(intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(PARIS).toInstant().toEpochMilli(), intent)
        }
    }

    fun cancel(context: Context) = schedule(context, emptyList(), LocalDateTime.now(PARIS))

    internal fun runNow(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val builder = OneTimeWorkRequestBuilder<FavoriteWatchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // Before Android 12, expedited work runs as a foreground service: the app declares none, so it stays ordinary work there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request = builder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

/**
 * The check runs right here, as Android allows for short work after an alarm (goAsync, about ten seconds):
 * queued instead, it could be held back while the phone sleeps, and the alarm would be of no use.
 * Should it take longer (timetables to download), the queue takes over.
 */
class DepartureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val done = withTimeoutOrNull(ALARM_CHECK_MILLIS) { FavoriteWatchWorker.check(context.applicationContext) }
                if (done == null) DepartureAlarms.runNow(context.applicationContext, ExistingWorkPolicy.KEEP)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ALARM_CHECK_MILLIS = 8_000L
    }
}
