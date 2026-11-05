package com.scresto.datrain.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scresto.datrain.R
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.StationGroups
import com.scresto.datrain.data.TimetableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatrainApp(
    openedFavorite: String? = null,
    onFavoriteOpened: () -> Unit = {},
    vm: SearchViewModel = viewModel(),
) {
    // Immediate: a text field receiving its value late can duplicate or lose letters.
    val state by vm.state.collectAsState(Dispatchers.Main.immediate)
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val toggleFavorite = { train: FavoriteTrain ->
        if (!state.recent.isFavorite(train) && state.recent.notifications) askNotifications()
        vm.toggleFavorite(train)
    }
    // The timetables may still be loading when a notification opens the app.
    LaunchedEffect(openedFavorite, state.timetable is TimetableState.Ready) {
        if (openedFavorite != null && state.timetable is TimetableState.Ready) {
            vm.openFavorite(openedFavorite)
            onFavoriteOpened()
        }
    }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The next runs change with time: they are read again when coming back to the app.
    LifecycleResumeEffect(Unit) {
        vm.refreshFavorites()
        onPauseOrDispose { }
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        state.trackedTrip?.let { trip ->
            // Opened from a journey with changes, the star is the whole journey's.
            val favorite = if (state.trackedJourney.size > 1) FavoriteTrain.of(state.trackedJourney) else FavoriteTrain.of(trip)
            TrainMapScreen(
                trip,
                journey = state.trackedJourney,
                onSwitch = { other -> vm.track(other, state.trackedJourney) },
                onBack = {
                    vm.track(null)
                    vm.refreshFavorites()
                },
                isFavorite = favorite != null && state.recent.isFavorite(favorite),
                onToggleFavorite = favorite?.let { { toggleFavorite(it) } },
            )
            return@MaterialTheme
        }
        if (state.showTrainSearch) {
            TrainSearchScreen(vm, state)
            return@MaterialTheme
        }
        if (state.showSources) {
            SourcesScreen(onBack = { vm.showSources(false) })
            return@MaterialTheme
        }
        BackHandler(enabled = state.hasSearched, onBack = vm::clearResults)
        if (showAbout) {
            AboutDialog(
                notifications = state.recent.notifications,
                onNotifications = { enabled ->
                    if (enabled) askNotifications()
                    vm.setNotifications(enabled)
                },
                onSources = {
                    showAbout = false
                    vm.showSources(true)
                },
                onTestNotification = {
                    askNotifications()
                    vm.sendTestNotification()
                },
                hasFavorites = state.recent.favorites.isNotEmpty(),
                onDismiss = { showAbout = false },
            )
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { DatrainLogo(onClick = { showAbout = true }) },
                    actions = {
                        IconButton(onClick = vm::clearResults) {
                            Icon(painterResource(R.drawable.ic_star), contentDescription = "Voir les favoris", tint = FAVORITE_YELLOW)
                        }
                        IconButton(onClick = { vm.showTrainSearch(true) }) {
                            Text(
                                "#",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "Rechercher un numéro de train" },
                            )
                        }
                        val now = LocalDateTime.now(PARIS)
                        val trains = state.recent.trains.filterNot { it.isOver(now) }
                        if (trains.isNotEmpty()) RecentTrainsMenu(trains, vm::reopenTrain, vm::forgetTrain)
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TimetableStatus(state.timetable, vm::retryDownload) }
                item { SearchForm(state, vm) }
                state.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                if (!state.hasSearched && state.recent.favorites.isEmpty()) {
                    item {
                        Text("Pas encore de favoris. Appuyez sur ☆ sur un train pour l'ajouter ici.")
                    }
                }
                if (!state.hasSearched && state.favoriteRuns.isNotEmpty()) {
                    item {
                        Text("★ Favoris", style = MaterialTheme.typography.titleMedium, color = FAVORITE_YELLOW)
                    }
                    items(state.favoriteRuns, key = { "fav-${it.favorite.key}" }) { run ->
                        FavoriteCard(
                            run = run,
                            onOpen = { it.rides?.let(vm::openRun) },
                            showBell = state.recent.notifications,
                            onBell = { vm.setFavoriteNotify(run.favorite, !run.favorite.notify) },
                            onRemove = {
                                vm.removeFavorite(run.favorite)
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    val result = snackbar.showSnackbar("Favori retiré", "Annuler", duration = SnackbarDuration.Short)
                                    if (result == SnackbarResult.ActionPerformed) vm.addFavorite(run.favorite)
                                }
                            },
                        )
                    }
                }
                state.suburbanMissingFrom?.let {
                    val until = (state.timetable as TimetableState.Ready).info.suburbanUntil!!
                    item {
                        Text("RER et Transilien : horaires connus jusqu'au ${until.format(SHORT_DAY_FORMAT)}. Ces trains ne sont pas proposés après cette date.")
                    }
                }
                if (state.realtimeUnavailable) {
                    item { Text("Temps réel indisponible : seuls les horaires prévus sont affichés.") }
                }
                state.itineraries.groupBy { it.departure.toLocalDate() }.forEach { (day, itineraries) ->
                    item(key = "day-$day") {
                        Text(
                            day.format(DAY_FORMAT).replaceFirstChar { it.uppercase(Locale.FRENCH) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(itineraries, key = { it.id }) { itinerary ->
                        ItineraryCard(
                            itinerary = itinerary,
                            alerts = state.alerts[itinerary.id],
                            alertsFailed = itinerary.id in state.alertsFailed,
                            onExpand = { vm.loadAlerts(itinerary) },
                            onTrack = { ride, itinerary -> vm.trackRide(ride, itinerary.rides.map { it.trip }) },
                            isFavorite = state.recent::isFavorite,
                            fromCity = StationGroups.named(state.from?.name.orEmpty()) != null,
                            toCity = StationGroups.named(state.to?.name.orEmpty()) != null,
                            onToggleFavorite = toggleFavorite,
                        )
                    }
                }
                if (state.isLoading) {
                    item { CenteredProgress() }
                }
                if (state.hasSearched && !state.isLoading && state.error == null && state.itineraries.isEmpty()) {
                    item {
                        Text(if (state.arriveBy) "Aucun trajet n'arrive avant cette heure." else "Aucun trajet ce jour-là à partir de cette heure.")
                    }
                }
                if (state.moreDay != null && !state.isLoading) {
                    item {
                        OutlinedButton(onClick = vm::loadMoreDay, modifier = Modifier.fillMaxWidth()) {
                            Text("Voir le ${formatDay(state.moreDay!!)}")
                        }
                    }
                }
                if (state.itineraries.any { it.changes > 0 }) {
                    item {
                        Text(
                            "Temps de changement estimés : vérifiez avant de voyager.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
