package com.scresto.datrain.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scresto.datrain.MainActivity
import com.scresto.datrain.R
import com.scresto.datrain.widget.DatrainWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Watches favorite trains in the background, with as little data as possible: most runs do no
 * network call at all. Android runs this about every 15 minutes at best, later when the phone sleeps.
 */
class FavoriteWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        check(applicationContext)
        return Result.success()
    }

    companion object {
        /**
         * One pass over the watched favorites: real time, notifications, widget, next departure alarm.
         * One at a time: the periodic check, an alarm and the widget could otherwise see the same change
         * and ring twice. [force]: asked by the user, real time is read at once whatever was read lately.
         * [fullFeed]: asked by the favorites list, the full feed it reads, so that the same delays are saved.
         */
        suspend fun check(context: Context, force: Boolean = false, fullFeed: Boolean = false) =
            running.withLock { checkNow(context, force, fullFeed) }

        private val running = Mutex()

        private suspend fun checkNow(context: Context, force: Boolean, fullFeed: Boolean) {
            val historyStore = RecentHistoryStore(context)
            val saved = historyStore.load()
            // The widget needs every favorite watched, even with notifications off; they then stay silent.
            val widget = DatrainWidget.isPlaced(context)
            if (saved.favorites.none { widget || (saved.notifications && it.notify) }) {
                DepartureAlarms.cancel(context)
                return
            }

            val repository = TimetableRepository.get(context)
            // The manifest is tiny; files are only downloaded when they changed, and as the data mode allows.
            repository.refreshWhenDue()
            val planner = repository.plannerOrNull() ?: return

            val store = FavoriteWatchStore(context)
            val now = LocalDateTime.now(PARIS)
            // A temporary follow goes away after its run: the list, the widget and the alarms move on.
            val history = saved.withoutFinishedFollows(planner, store.load(), now)
            if (history != saved) historyStore.save(history)
            val favorites = history.favorites.filter { widget || (history.notifications && it.notify) }
            if (favorites.isEmpty()) {
                DepartureAlarms.cancel(context)
                DatrainWidget.refresh(context)
                return
            }
            // Kept two days: enough to never look twice for the same run.
            val lateChecked = store.lateChecked().filter { it.substringAfterLast('@') >= now.minusDays(1).toString() }.toSet()
            val policy = DataPolicy.current(context)
            // Put off on mobile data: caught up as soon as the phone is on Wi-Fi.
            if (!policy.generous) WifiCatchUpWorker.schedule(context)
            val result = FavoriteCheck.run(
                favorites, { history.notifications && it.notify }, planner, store.load(), lateChecked, NetworkLiveSource(context), now, force, fullFeed,
                policy.generous,
            )
            result.notices.forEach { notice ->
                val replacing = cancelledRun(notice, history, result.states)
                val alternatives = replacing?.let { (favorite, leaves) -> Alternatives.find(planner, favorite, leaves, null, now) }
                notify(context, notice.train, notice.departure, notice.changes, notice.opens, alternatives, replacing?.second, now)
            }
            store.save(result.states)
            store.saveLateChecked(result.lateChecked)
            DatrainWidget.refresh(context)
            DepartureAlarms.schedule(context, result.departures, now, result.arrivals, result.trips)
        }

        private const val NAME = "surveillance-favoris"
        private const val CHANNEL = "favoris"
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

        /** For test builds: shows the current state of each favorite, without changing what is remembered. */
        suspend fun sendTest(context: Context, favorites: List<FavoriteTrain>) {
            val planner = TimetableRepository.get(context).plannerOrNull() ?: return
            val live = NetworkLiveSource(context)
            val now = LocalDateTime.now(PARIS)
            for (favorite in favorites) {
                val rides = if (favorite.isJourney) planner.nextJourney(favorite, now) else null
                favorite.legs.forEachIndexed { i, leg ->
                    val ride = if (favorite.isJourney) rides?.getOrNull(i) else planner.nextRun(favorite, now)
                    val state = try {
                        FavoriteCheck.read(leg, null, ride, live, now)
                    } catch (e: NomadeError) {
                        show(context, leg, null, "Test : réseau indisponible", favorite.key)
                        return@forEachIndexed
                    } catch (e: IOException) {
                        show(context, leg, null, "Test : réseau indisponible", favorite.key)
                        return@forEachIndexed
                    }
                    show(context, leg, state?.departureTime, "Test : ${testStatus(state)}", favorite.key)
                }
            }
        }

        private fun testStatus(state: WatchState?): String {
            val status = when {
                state == null -> "ne circule plus dans les horaires connus"
                state.cancelled -> "train supprimé"
                else -> describeDelay(state.departureDelay, state.arrivalDelay).replaceFirstChar { it.lowercase() }
            }
            return (listOf(status) + state?.alerts.orEmpty()).joinToString("\n")
        }

        /**
         * For a cancellation not already replaced by a temporary follow: the favorite, and when its run was to
         * leave, the first train of a journey.
         */
        private fun cancelledRun(notice: Notice, history: RecentHistory, states: Map<String, WatchState>): Pair<FavoriteTrain, LocalDateTime>? {
            if (notice.changes.none { it == FavoriteChange.Cancelled || it is FavoriteChange.Removed }) return null
            val favorite = history.favorites.firstOrNull { it.key == notice.opens } ?: return null
            val removed = notice.changes.filterIsInstance<FavoriteChange.Removed>().firstOrNull()
            val leaves = removed?.departure ?: states[FavoriteWatch.legKeys(favorite).first()]?.departureTime ?: notice.departure
            if (TemporaryFollows.replacement(favorite, leaves.toLocalDate(), history.favorites) != null) return null
            return favorite to leaves
        }

        /**
         * [openKey]: the favorite the notification opens, a whole journey for one of its trains. For a
         * cancellation, the trains before and after it, and a button for the others.
         */
        private fun notify(
            context: Context,
            favorite: FavoriteTrain,
            departure: LocalDateTime,
            changes: List<FavoriteChange>,
            openKey: String = favorite.key,
            alternatives: Alternatives? = null,
            cancelledLeaves: LocalDateTime? = null,
            now: LocalDateTime = LocalDateTime.now(PARIS),
        ) {
            val text = changes.joinToString("\n", transform = ::describe) +
                alternatives?.takeUnless { it.isEmpty }?.let { "\n" + describe(it, now) }.orEmpty()
            show(context, favorite, departure, text, openKey, cancelledLeaves)
        }

        /** "Plus tôt : 17:09 (dans 12 min) · Suivant : 18:40". */
        internal fun describe(alternatives: Alternatives, now: LocalDateTime): String = listOfNotNull(
            alternatives.before?.let {
                val leaves = it.rides.first().trip.estimatedDeparture
                val minutes = java.time.Duration.between(now, leaves).toMinutes()
                "Plus tôt : ${leaves.format(CLOCK)}" + if (minutes in 0..59) " (dans $minutes min)" else ""
            },
            alternatives.after?.let { "Suivant : ${it.rides.first().trip.estimatedDeparture.format(CLOCK)}" },
        ).joinToString(" · ")

        private fun show(
            context: Context,
            favorite: FavoriteTrain,
            departure: LocalDateTime?,
            text: String,
            openKey: String = favorite.key,
            cancelledLeaves: LocalDateTime? = null,
        ) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            createChannel(context)
            val intent = Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FAVORITE, openKey)
            val pending = PendingIntent.getActivity(
                context, "$openKey/${favorite.key}".hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_train)
                .setContentTitle("${favorite.product} ${favorite.number} · ${favorite.fromName} ${departure?.format(CLOCK).orEmpty()}".trim())
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .apply {
                    // The search between the same stations, from the train before it still possible.
                    cancelledLeaves?.let { leaves ->
                        val search = Intent(context, MainActivity::class.java)
                            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(MainActivity.EXTRA_ALTERNATIVES, openKey)
                            .putExtra(MainActivity.EXTRA_LEAVES, leaves.toString())
                        val action = PendingIntent.getActivity(
                            context, "alternatives/$openKey".hashCode(), search,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        addAction(0, "Autres trains", action)
                    }
                }
                .build()
            try {
                manager.notify("$openKey/${favorite.key}".hashCode(), notification)
            } catch (e: SecurityException) {
                // Permission just withdrawn: nothing to do.
            }
        }

        private fun describe(change: FavoriteChange): String = when (change) {
            FavoriteChange.Cancelled -> "Train supprimé"
            FavoriteChange.Restored -> "Train rétabli"
            is FavoriteChange.Removed -> "Train supprimé le ${change.departure.format(DAY)}"
            is FavoriteChange.ScheduleChanged -> "Nouvel horaire : départ à ${change.departure.format(CLOCK)}"
            is FavoriteChange.Delayed -> describeDelay(change.departure, change.arrival, told = true)
            is FavoriteChange.PlatformChanged -> "Changement de voie : voie ${change.platform}"
            is FavoriteChange.PlatformAnnounced -> "Voie annoncée : voie ${change.platform}"
            is FavoriteChange.NewAlert -> change.title
            is FavoriteChange.ConnectionAtRisk -> "Correspondance menacée à ${change.station} : ${change.minutes} min pour changer"
        }

        /**
         * "Retard : +10 min" when departure and arrival agree, both otherwise; only the arrival once the
         * train has left ([departure] null). [told]: back to on time after a delay was told.
         */
        internal fun describeDelay(departure: Int?, arrival: Int, told: Boolean = false): String {
            fun part(minutes: Int) = if (minutes > 0) "+$minutes min" else "à l'heure"
            val onTime = if (told) "De nouveau à l'heure" else "À l'heure"
            return when {
                departure == null -> if (arrival > 0) "Retard à l'arrivée : +$arrival min" else "$onTime à l'arrivée"
                departure <= 0 && arrival <= 0 -> onTime
                departure == arrival -> "Retard : +$arrival min"
                else -> "Départ ${part(departure)}, arrivée ${part(arrival)}"
            }
        }

        /** On while there is something to watch, off otherwise. */
        fun sync(context: Context, history: RecentHistory) {
            val manager = WorkManager.getInstance(context)
            val notifying = history.notifications && history.favorites.any { it.notify }
            if (notifying || (DatrainWidget.isPlaced(context) && history.favorites.isNotEmpty())) {
                val request = PeriodicWorkRequestBuilder<FavoriteWatchWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                manager.cancelUniqueWork(NAME)
                DepartureAlarms.cancel(context)
            }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Trains favoris", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Suppressions, retards, voies et messages de vos trains favoris"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** Real time from the network; what one round of checks has downloaded is read only once. */
private class NetworkLiveSource(context: Context) : LiveSource {
    private val nomade = NomadeApi(Http.client(context))
    private val boards = HashMap<Int, Board>()
    private var feed: LiveFeed? = null
    private var updates: List<RealtimeTrip>? = null
    private val realtime = GtfsRealtimeApi(Http.client(context))
    private val alertsCache = AlertsCache.get(context)

    /** An out-of-date feed counts as no feed: the check is skipped and tried again, rather than trusting old delays. */
    override suspend fun feed(): LiveFeed = feed ?: nomade.liveFeed().also {
        if (OffsetDateTime.now(PARIS) >= it.staleAfter) throw NomadeError.Api("out-of-date feed")
        feed = it
    }

    override suspend fun board(uic: Int): Board = boards[uic] ?: nomade.board(uic.toString()).also { boards[uic] = it }

    override suspend fun alerts(): List<ServiceAlert> = alertsCache.get()

    override suspend fun tripUpdates(): List<RealtimeTrip> = updates ?: realtime.tripUpdates().also { updates = it }
}
