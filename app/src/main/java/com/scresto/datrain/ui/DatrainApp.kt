package com.scresto.datrain.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.scresto.datrain.data.DataMode
import com.scresto.datrain.data.RecentTrain
import com.scresto.datrain.widget.DatrainWidget
import com.scresto.datrain.widget.WidgetPin
import com.scresto.datrain.data.TemporaryFollows
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.StationGroups
import com.scresto.datrain.data.TimetableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

private const val SHOWN_REFRESH_MILLIS = 2 * 60_000L

/** The app, with the guided tour over every screen. */
@Composable
fun DatrainRoot(
    openedFavorite: String? = null,
    onFavoriteOpened: () -> Unit = {},
    openedAlternatives: Pair<String, String>? = null,
    onAlternativesOpened: () -> Unit = {},
) {
    val vm: SearchViewModel = viewModel()
    val state by vm.state.collectAsState(Dispatchers.Main.immediate)
    androidx.compose.runtime.CompositionLocalProvider(LocalTourTarget provides state.tour?.let { TOUR.getOrNull(it)?.target }) {
        Box(Modifier.fillMaxSize()) {
            DatrainApp(openedFavorite, onFavoriteOpened, openedAlternatives, onAlternativesOpened, vm)
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                TourOverlay(state, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatrainApp(
    openedFavorite: String? = null,
    onFavoriteOpened: () -> Unit = {},
    openedAlternatives: Pair<String, String>? = null,
    onAlternativesOpened: () -> Unit = {},
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
    // A new favorite asks first which days the traveller takes it; the same dialog changes them later.
    var daysFor by remember { mutableStateOf<Pair<FavoriteTrain, Boolean>?>(null) }
    val toggleFavorite = { train: FavoriteTrain ->
        if (state.recent.isFavorite(train)) vm.toggleFavorite(train) else daysFor = train to true
    }
    // The timetables may still be loading when a notification opens the app.
    LaunchedEffect(openedFavorite, state.timetable is TimetableState.Ready) {
        if (openedFavorite != null && state.timetable is TimetableState.Ready) {
            vm.openFavorite(openedFavorite)
            onFavoriteOpened()
        }
    }
    LaunchedEffect(openedAlternatives, state.timetable is TimetableState.Ready) {
        if (openedAlternatives != null && state.timetable is TimetableState.Ready) {
            val (key, leaves) = openedAlternatives
            runCatching { LocalDateTime.parse(leaves) }.getOrNull()?.let { vm.searchAlternatives(key, it) }
            onAlternativesOpened()
        }
    }
    // The widget tip only where the launcher can place it in one tap, and while it is not placed yet.
    val widgetPinnable = remember(state.recent.favorites.size) {
        WidgetPin.isSupported(context) && !DatrainWidget.isPlaced(context)
    }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The next runs change with time: they are read again when coming back to the app.
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    // Results and favorites stay current while on screen, not only when coming back to it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(SHOWN_REFRESH_MILLIS)
                vm.refreshShown()
            }
        }
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        // Everyone sees the tour once, after choosing the data mode.
        LaunchedEffect(state.recent.introSeen, state.recent.dataMode, state.tour) {
            if (!state.recent.introSeen && state.recent.dataMode != null && state.tour == null) vm.startTour()
        }
        daysFor?.let { (train, adding) ->
            val running = remember(train.key, state.timetable) {
                (state.timetable as? TimetableState.Ready)?.planner?.runningDays(train).orEmpty()
            }
            // The run seen, followed alone if wished: until 30 minutes after it arrives.
            val temporaryRun = remember(train.key, state.timetable, state.trackedTrip) {
                val day = (state.trackedJourney.firstOrNull() ?: state.trackedTrip)?.serviceDate
                val planner = (state.timetable as? TimetableState.Ready)?.planner
                if (!adding || day == null || planner == null) {
                    null
                } else {
                    val follow = train.copy(run = day.toString(), days = emptyList())
                    val now = LocalDateTime.now(PARIS)
                    planner.nextJourney(follow, now)?.takeIf { !TemporaryFollows.isOver(follow, planner, emptyMap(), now) }?.first()?.trip?.departure
                }
            }
            FavoriteDaysDialog(
                train, running, adding,
                onDismiss = { daysFor = null },
                temporaryRun = temporaryRun,
                preferTemporary = state.trackedAsAlternative,
            ) { days, temporary ->
                daysFor = null
                if (adding) {
                    if (state.recent.notifications) askNotifications()
                    val day = (state.trackedJourney.firstOrNull() ?: state.trackedTrip)?.serviceDate
                    vm.addFavorite(if (temporary && day != null) train.copy(run = day.toString(), days = emptyList()) else train.copy(days = days))
                } else {
                    vm.setFavoriteDays(train, days)
                }
            }
        }
        state.trackedTrip?.let { trip ->
            // Opened from a journey with changes, the star is the whole journey's.
            val favorite = if (state.trackedJourney.size > 1) FavoriteTrain.of(state.trackedJourney) else FavoriteTrain.of(trip)
            // Found by its number, the train has no stations of the traveller's: they are asked for first.
            val byNumber = state.trackedJourney.size <= 1 && trip.fromKey.isEmpty()
            val numberFavorite = if (byNumber) state.recent.favorites.firstOrNull { !it.isJourney && it.number == trip.number } else null
            val savedFavorite = numberFavorite ?: favorite?.let { f -> state.recent.favorites.firstOrNull { it.isSameAs(f) } }
            var pickStations by rememberSaveable { mutableStateOf(false) }
            if (pickStations) {
                FavoriteStationsDialog(trip, onDismiss = { pickStations = false }) { chosen ->
                    pickStations = false
                    toggleFavorite(chosen)
                }
            }
            TrainMapScreen(
                trip,
                journey = state.trackedJourney,
                onSwitch = { other -> vm.track(other, state.trackedJourney) },
                onBack = {
                    vm.track(null)
                    vm.refreshFavorites()
                },
                isFavorite = if (byNumber) numberFavorite != null else favorite != null && state.recent.isFavorite(favorite),
                onDays = savedFavorite?.takeUnless { it.isTemporary }?.let { saved -> { daysFor = saved to false } },
                onAlternatives = { vm.searchAlternatives(trip) },
                stopsTip = if (state.tour == null && Tips.STOPS !in state.recent.tipsSeen) { { vm.seeTip(Tips.STOPS) } } else null,
                daysRestricted = savedFavorite?.days?.isNotEmpty() == true,
                onToggleFavorite = when {
                    numberFavorite != null -> { { vm.removeFavorite(numberFavorite) } }
                    byNumber -> { { pickStations = true } }
                    else -> favorite?.let { { toggleFavorite(it) } }
                },
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
        if (state.recent.dataMode == null) DataModeDialog(vm::setDataMode)
        if (showAbout) {
            AboutDialog(
                notifications = state.recent.notifications,
                onNotifications = { enabled ->
                    if (enabled) askNotifications()
                    vm.setNotifications(enabled)
                },
                dataMode = state.recent.dataMode ?: DataMode.REACTIVE,
                onDataMode = vm::setDataMode,
                onSources = {
                    showAbout = false
                    vm.showSources(true)
                },
                onTestNotification = {
                    askNotifications()
                    vm.sendTestNotification()
                },
                hasFavorites = state.recent.favorites.isNotEmpty(),
                onIntro = {
                    showAbout = false
                    vm.startTour()
                },
                onDismiss = { showAbout = false },
            )
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Box(Modifier.tourTarget(TourTargets.LOGO)) { DatrainLogo(onClick = { showAbout = true }) } },
                    actions = {
                        IconButton(onClick = vm::clearResults) {
                            Icon(painterResource(R.drawable.ic_star), contentDescription = "Voir les favoris", tint = FAVORITE_YELLOW)
                        }
                        IconButton(onClick = { vm.showTrainSearch(true) }, modifier = Modifier.tourTarget(TourTargets.NUMBER)) {
                            Text(
                                "#",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "Rechercher un numéro de train" },
                            )
                        }
                        val now = LocalDateTime.now(PARIS)
                        val trains = state.recent.trains.filterNot { it.isOver(now) }
                        // During the tour, the example's train stands for the trains to come.
                        val shown = trains.ifEmpty {
                            if (state.tour != null) state.itineraries.firstOrNull()?.rides?.firstOrNull()?.let { listOf(RecentTrain.of(it)) }.orEmpty() else emptyList()
                        }
                        if (shown.isNotEmpty()) RecentTrainsMenu(shown, vm::reopenTrain, vm::forgetTrain)
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
                    // One tip at a time, the most useful first.
                    val seen = state.recent.tipsSeen
                    val favoritesTip = when {
                        Tips.ALTERNATIVE !in seen && state.favoriteRuns.any { it.alternatives?.isEmpty == false } ->
                            Tips.ALTERNATIVE to "Touchez un train proposé pour le suivre à la place."
                        Tips.WIDGET !in seen && widgetPinnable ->
                            Tips.WIDGET to "Ajoutez le widget pour voir votre prochain train sur l'écran d'accueil."
                        Tips.DAYS !in seen -> Tips.DAYS to "Appui long sur un favori pour choisir vos jours."
                        else -> null
                    }
                    favoritesTip?.takeIf { state.tour == null }?.let { (tip, text) ->
                        item(key = "tip-$tip") {
                            Tip(
                                text,
                                onDismiss = { vm.seeTip(tip) },
                                action = if (tip == Tips.WIDGET) {
                                    "Ajouter le widget" to {
                                        scope.launch { WidgetPin.request(context) }
                                        vm.seeTip(tip)
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    items(state.favoriteRuns, key = { "fav-${it.favorite.key}" }) { run ->
                        FavoriteCard(
                            run = run,
                            onOpen = { it.rides?.let(vm::openRun) },
                            showBell = state.recent.notifications,
                            onBell = { vm.setFavoriteNotify(run.favorite, !run.favorite.notify) },
                            onDays = { daysFor = run.favorite to false },
                            onAlternative = vm::openAlternative,
                            isFollowed = { itinerary ->
                                FavoriteTrain.of(itinerary.rides.map { it.trip })?.let(state.recent::isFavorite) == true
                            },
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
                // Before the time asked, trains that may still be caught: a tap adds the hour before.
                if (state.canLoadEarlier) {
                    item(key = "earlier") {
                        OutlinedButton(onClick = vm::loadEarlier, modifier = Modifier.fillMaxWidth()) { Text("Trains plus tôt") }
                    }
                }
                if (state.tour == null && state.itineraries.isNotEmpty() && Tips.STAR !in state.recent.tipsSeen) {
                    item(key = "tip-star") {
                        Tip("Touchez ☆ pour suivre ce train dans vos favoris.", onDismiss = { vm.seeTip(Tips.STAR) })
                    }
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
                            tourFirst = state.tour != null && itinerary.id == state.itineraries.firstOrNull()?.id,
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
