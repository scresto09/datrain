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
        val history = RecentHistoryStore(applicationContext).load()
        // The widget needs every favorite watched, even with notifications off; they then stay silent.
        val widget = DatrainWidget.isPlaced(applicationContext)
        val favorites = history.favorites.filter { widget || (history.notifications && it.notify) }
        if (favorites.isEmpty()) return Result.success()

        val repository = TimetableRepository.get(applicationContext)
        // The manifest is tiny; files are only downloaded when they changed.
        repository.refreshIfOlderThan(TIMETABLE_CHECK_MILLIS)
        val planner = repository.plannerOrNull() ?: return Result.success()

        val live = LiveSources(applicationContext)
        val store = FavoriteWatchStore(applicationContext)
        val states = store.load().toMutableMap()
        val now = LocalDateTime.now(PARIS)

        for (favorite in favorites) {
            val tell = history.notifications && favorite.notify
            // A journey is watched train by train, then its changes.
            val rides = planner.nextJourney(favorite, FavoriteWatch.watchedFrom(favorite, states, now))
            val legStates = favorite.legs.mapIndexed { i, leg ->
                val key = if (favorite.isJourney) "${favorite.key}#$i" else favorite.key
                val old = states[key]
                val ride = rides?.getOrNull(i)
                val state = try {
                    read(leg, old, ride, live, now)
                } catch (e: NomadeError) {
                    null
                } catch (e: IOException) {
                    null
                } ?: return@mapIndexed null
                val changes = FavoriteWatch.changes(old, state, now)
                if (tell && changes.isNotEmpty()) notify(applicationContext, leg, state.departureTime, changes, favorite.key)
                FavoriteWatch.remembered(old, state, changes).also { states[key] = it }
            }
            legStates.zipWithNext().forEachIndexed { i, (before, after) ->
                if (before == null || after == null) return@forEachIndexed
                val minutes = FavoriteWatch.connectionAtRisk(before, after, TIGHT_CONNECTION_MINUTES) ?: return@forEachIndexed
                val next = favorite.legs[i + 1]
                if (tell) notify(applicationContext, next, after.departureTime, listOf(FavoriteChange.ConnectionAtRisk(next.fromName, minutes)), favorite.key)
                states["${favorite.key}#${i + 1}"] = after.copy(connectionWarned = true)
            }
        }
        store.save(states.filterKeys { key -> favorites.any { key == it.key || key.startsWith(it.key + "#") } })
        DatrainWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "surveillance-favoris"
        private const val CHANNEL = "favoris"
        private const val TIMETABLE_CHECK_MILLIS = 6 * 3600 * 1000L
        /** Same threshold as "Correspondance menacée" on the results. */
        private const val TIGHT_CONNECTION_MINUTES = 5L
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

        /** For test builds: shows the current state of each favorite, without changing what is remembered. */
        suspend fun sendTest(context: Context, favorites: List<FavoriteTrain>) {
            val planner = TimetableRepository.get(context).plannerOrNull() ?: return
            val live = LiveSources(context)
            val now = LocalDateTime.now(PARIS)
            for (favorite in favorites) {
                val rides = if (favorite.isJourney) planner.nextJourney(favorite, now) else null
                favorite.legs.forEachIndexed { i, leg ->
                    val ride = if (favorite.isJourney) rides?.getOrNull(i) else planner.nextRun(favorite, now)
                    val state = try {
                        read(leg, null, ride, live, now)
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
                state.delayMinutes > 0 -> "retard de ${state.delayMinutes} min"
                else -> "à l'heure"
            }
            return (listOf(status) + state?.alerts.orEmpty()).joinToString("\n")
        }

        /**
         * Null when there is nothing to watch. A first look reads the full feed once, because a run
         * cancelled in advance has left the timetables. It throws without network, to be tried again.
         */
        private suspend fun read(
            favorite: FavoriteTrain,
            old: WatchState?,
            ride: RideLeg?,
            live: LiveSources,
            now: LocalDateTime,
        ): WatchState? {
            val cancelled = if (old == null) FavoriteWatch.cancelledRun(favorite, ride, live.feed(), now) else null
            if (cancelled != null) return FavoriteWatch.stateFor(cancelled, null).copy(cancelled = true)
            // No run left at all: the expected one is gone.
            if (ride == null) return if (old != null && old.arrivalTime > now) old.copy(cancelled = true) else null
            var state = FavoriteWatch.stateFor(ride, old)
            try {
                when (FavoriteWatch.liveCheck(state, now)) {
                    LiveCheck.NONE -> Unit
                    LiveCheck.FULL_FEED -> state = FavoriteWatch.withFeed(state, live.feed(), ride)
                    LiveCheck.STATION_BOARD -> {
                        val boarding = now < state.departureTime.plusMinutes(state.delayMinutes.toLong())
                        val uic = if (boarding) favorite.fromUic else favorite.toUic
                        if (uic != 0) {
                            state = FavoriteWatch.withBoard(state, live.board(uic), ride, boarding, OffsetDateTime.now(PARIS))
                        }
                    }
                }
                if (FavoriteWatch.needsAlerts(state, now)) {
                    val alerts = TrainAlerts.of(live.alerts.get(), ride.trip)
                    val titles = alerts.train + alerts.byStation[ride.trip.fromKey].orEmpty() + alerts.byStation[ride.trip.toKey].orEmpty()
                    state = state.copy(alerts = titles.map { it.title }.toSet(), alertsDay = now.toLocalDate().toString())
                }
            } catch (e: NomadeError) {
                // Next time: this check is simply skipped.
            } catch (e: IOException) {
                // Same.
            }
            return state
        }

        /** [openKey]: the favorite the notification opens, a whole journey for one of its trains. */
        private fun notify(
            context: Context,
            favorite: FavoriteTrain,
            departure: LocalDateTime,
            changes: List<FavoriteChange>,
            openKey: String = favorite.key,
        ) {
            show(context, favorite, departure, changes.joinToString("\n", transform = ::describe), openKey)
        }

        private fun show(context: Context, favorite: FavoriteTrain, departure: LocalDateTime?, text: String, openKey: String = favorite.key) {
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
                .build()
            try {
                manager.notify("$openKey/${favorite.key}".hashCode(), notification)
            } catch (e: SecurityException) {
                // Permission just withdrawn: nothing to do.
            }
        }

        private fun describe(change: FavoriteChange): String = when (change) {
            FavoriteChange.Cancelled -> "Train supprimé"
            is FavoriteChange.Removed -> "Train supprimé le ${change.departure.format(DAY)}"
            is FavoriteChange.ScheduleChanged -> "Nouvel horaire : départ à ${change.departure.format(CLOCK)}"
            is FavoriteChange.Delayed -> if (change.minutes > 0) "Retard : +${change.minutes} min" else "De nouveau à l'heure"
            is FavoriteChange.PlatformChanged -> "Changement de voie : voie ${change.platform}"
            is FavoriteChange.NewAlert -> change.title
            is FavoriteChange.ConnectionAtRisk -> "Correspondance menacée à ${change.station} : ${change.minutes} min pour changer"
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

/** What one round of checks has already downloaded. */
private class LiveSources(context: Context) {
    private val nomade = NomadeApi(Http.client(context))
    private val boards = HashMap<Int, Board>()
    private var feed: LiveFeed? = null
    val alerts = AlertsCache.get(context)

    suspend fun feed(): LiveFeed = feed ?: nomade.liveFeed().also { feed = it }

    suspend fun board(uic: Int): Board = boards[uic] ?: nomade.board(uic.toString()).also { boards[uic] = it }
}
