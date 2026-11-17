package com.scresto.datrain.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.OffsetDateTime

/** How much Datrain downloads on mobile data. On Wi-Fi, every mode behaves as [REACTIVE]. */
@Serializable
enum class DataMode { REACTIVE, BALANCED, SAVER }

/** What a mode allows on the network in use: one rule for the timetables, the background check and the alarms. */
data class DataPolicy(val mode: DataMode, val unmetered: Boolean) {
    /** Everything as often as useful: [DataMode.REACTIVE], or any mode on Wi-Fi. */
    val generous: Boolean get() = mode == DataMode.REACTIVE || unmetered

    /** How often the timetables' tiny manifest is read. A newer version waiting to be downloaded is read again soon. */
    fun timetableCheckEvery(pending: Boolean): Duration = if (generous || pending) TIMETABLE_CHECK else SPARING_EVERY

    /**
     * Whether timetable files published at [publishedAt] may be downloaded now. On mobile data, [DataMode.BALANCED]
     * waits for Wi-Fi up to 6 hours, [DataMode.SAVER] up to 24: past that, old timetables would miss cancellations.
     */
    fun mayDownloadTimetables(publishedAt: OffsetDateTime?, now: OffsetDateTime): Boolean {
        if (generous) return true
        val wait = if (mode == DataMode.BALANCED) SPARING_EVERY else SAVER_TIMETABLE_WAIT
        return publishedAt == null || now >= publishedAt.plus(wait)
    }

    companion object {
        val TIMETABLE_CHECK: Duration = Duration.ofMinutes(30)
        /** On mobile data, outside [DataMode.REACTIVE]: messages and timetable checks. */
        val SPARING_EVERY: Duration = Duration.ofHours(6)
        val SAVER_TIMETABLE_WAIT: Duration = Duration.ofHours(24)

        /** The mode chosen, or [DataMode.REACTIVE] before any choice, and the network in use now. */
        fun current(context: Context): DataPolicy =
            DataPolicy(RecentHistoryStore(context).load().dataMode ?: DataMode.REACTIVE, isUnmetered(context))

        /** Wi-Fi in practice: a network not charged by volume. A phone's hotspot is reported as charged. */
        fun isUnmetered(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}
