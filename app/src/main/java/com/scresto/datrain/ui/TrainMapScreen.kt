package com.scresto.datrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scresto.datrain.R
import com.scresto.datrain.data.InitialMapView
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.StopRow
import com.scresto.datrain.data.StopRows
import com.scresto.datrain.data.StopStatus
import com.scresto.datrain.data.TrainAlerts
import com.scresto.datrain.data.TrainProgress
import com.scresto.datrain.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private const val MAP_RESIZE_MILLIS = 150L
private val ROUTE_PASSED = ComposeColor(0xFF8A8F98)
private val ROUTE_AHEAD = ComposeColor(0xFF0B5FA5)
private val TRAIN_RED = ComposeColor(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainMapScreen(
    trip: Trip,
    onBack: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    journey: List<Trip> = emptyList(),
    onSwitch: (Trip) -> Unit = {},
    vm: TrainMapViewModel = viewModel(),
) {
    val state by vm.state.collectAsState(Dispatchers.Main.immediate)
    val others by vm.others.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    BackHandler(onBack = onBack)
    // Off screen, no network calls at all.
    LaunchedEffect(trip, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.follow(trip) }
    }
    LaunchedEffect(journey) { vm.loadOthers(journey) }
    val otherRoutes = remember(journey, trip, others) {
        journey.filter { routeKey(it) != routeKey(trip) }.mapNotNull { others[routeKey(it)] }
    }
    // Minutes to change trains, at both stations of each change.
    val changes = remember(journey) {
        buildMap {
            journey.zipWithNext { before, after ->
                val minutes = java.time.Duration.between(before.estimatedArrival, after.estimatedDeparture).toMinutes()
                put(before.toKey, minutes)
                put(after.fromKey, minutes)
            }
        }
    }
    val changePositions = (listOfNotNull(state.route) + otherRoutes)
        .flatMap { it.stops }
        .filter { it.uic in changes && it.position != null }
        .mapNotNull { it.position }
        .distinct()

    val context = LocalContext.current
    val controller = remember {
        // Must come before creating the view.
        MapLibre.getInstance(context)
        // Opens on the train's region rather than on the whole world.
        val view = InitialMapView.around(trip.plannedStations.values.mapNotNull { it.position })
        val camera = CameraPosition.Builder().target(LatLng(view.center.lat, view.center.lon)).zoom(view.zoom).build()
        val options = MapLibreMapOptions.createFromAttributes(context).camera(camera)
        TrainMapController(context, MapView(context, options).also { it.onCreate(null) })
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showStops by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showStops) {
        delay(MAP_RESIZE_MILLIS)
        controller.frameRoute()
    }
    SideEffect { controller.onSelected = { selected = it } }

    val progress = (state.status as? TrainStatus.Live)?.progress
    val rows = remember(state.train, state.route, progress, trip) {
        val train = state.train
        val route = state.route
        if (train == null || route == null) emptyList() else StopRows.build(train, route.stationNames, progress, trip)
    }
    // Once the traveller has left this train, the map moves on to the next one, once.
    var leftTrains by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val alighted = progress == TrainProgress.Arrived || rows.lastOrNull { it.isAlighting }?.status == StopStatus.PASSED
    LaunchedEffect(alighted, state.trip, trip) {
        val key = routeKey(trip)
        if (!alighted || state.trip != trip || key in leftTrains) return@LaunchedEffect
        val next = journey.getOrNull(journey.indexOfFirst { routeKey(it) == key } + 1) ?: return@LaunchedEffect
        leftTrains = leftTrains + key
        onSwitch(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${trip.product} ${trip.number}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = { onToggleFavorite?.let { FavoriteStar(isFavorite, it) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (journey.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    journey.forEach { leg ->
                        FilterChip(
                            selected = routeKey(leg) == routeKey(trip),
                            onClick = { if (routeKey(leg) != routeKey(trip)) onSwitch(leg) },
                            label = { Text("${leg.product} ${leg.number}") },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TrainMap(
                    controller = controller,
                    route = state.route,
                    progress = progress,
                    position = state.position,
                    others = otherRoutes,
                    changes = changePositions,
                    modifier = Modifier.fillMaxSize(),
                )
                selected?.let { MapBubble(it, state, rows, trip, controller, changes) }
            }
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                InfoPanel(
                    state, rows, StopRows.trainPosition(rows, progress), showStops, { showStops = it },
                    onTrainSelected = {
                        selected = SELECTED_TRAIN
                        state.position?.let(controller::focus)
                    },
                ) { row ->
                    selected = row.uic
                    row.activeIndex?.let { state.route?.stops?.getOrNull(it)?.position }?.let(controller::focus)
                }
            }
        }
    }
}

/** What a tap on the map opened: this value for the train, otherwise a station's UIC code. */
internal const val SELECTED_TRAIN = "train"

@Composable
private fun BoxScope.MapBubble(
    selected: String,
    state: TrainMapState,
    rows: List<StopRow>,
    trip: Trip,
    controller: TrainMapController,
    changes: Map<String, Long>,
) {
    val row = rows.firstOrNull { it.uic == selected }
    val position = if (selected == SELECTED_TRAIN) {
        state.position
    } else {
        row?.activeIndex?.let { state.route?.stops?.getOrNull(it)?.position }
    } ?: return
    if (selected != SELECTED_TRAIN && row == null) return

    controller.cameraMoves // Read so that the bubble follows the camera.
    val point = controller.screenPoint(position) ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var parent by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.matchParentSize().onSizeChanged { parent = it })
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .onSizeChanged { size = it }
            .offset {
                val gap = 36.dp.roundToPx()
                val above = point.y.roundToInt() - size.height - gap
                // Without room above, it goes below rather than over the point it describes.
                val y = if (above >= 0) above else point.y.roundToInt() + gap
                IntOffset(
                    (point.x.roundToInt() - size.width / 2).coerceIn(0, (parent.width - size.width).coerceAtLeast(0)),
                    y.coerceIn(0, (parent.height - size.height).coerceAtLeast(0)),
                )
            },
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .widthIn(max = 280.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (selected == SELECTED_TRAIN) TrainBubble(state, rows, trip) else row?.let { StationBubble(it, state, changes[it.uic]) }
        }
    }
}

@Composable
private fun StationBubble(row: StopRow, state: TrainMapState, changeMinutes: Long?) {
    val hasRealtime = state.train?.hasRealtime == true
    val word = if (row.isDeparture) "Départ prévu" else "Prévue"
    val estimated = if (row.isDeparture) "Départ estimé" else "Estimée"
    Text(row.name, fontWeight = FontWeight.SemiBold)
    row.planned?.let {
        Text("$word : ${clock(it)}" + if (hasRealtime && row.estimated == null) " (à l'heure)" else "")
    }
    row.estimated?.let { Text("$estimated : ${clock(it)}", color = delayColor()) }
    row.platform?.let { Text("Voie $it") }
    if (!hasRealtime) Text("Pas de temps réel", style = MaterialTheme.typography.bodySmall)
    changeMinutes?.let { Text("Correspondance : ${formatMinutes(it.toInt())}", fontWeight = FontWeight.SemiBold) }
    state.alerts.byStation[row.uic]?.let { AlertTexts(it) }
}

@Composable
private fun TrainBubble(state: TrainMapState, rows: List<StopRow>, trip: Trip) {
    val next = rows.firstOrNull { it.status == StopStatus.NEXT }
    val delay = next?.let { ((it.estimated ?: it.planned ?: 0) - (it.planned ?: 0)) / 60 } ?: 0
    val status = when {
        state.train?.hasRealtime != true -> "Horaires prévus"
        delay > 0 -> "+$delay min"
        else -> "À l'heure"
    }
    Text("${trip.product} ${trip.number} · $status", fontWeight = FontWeight.SemiBold)
    // Not left yet, or standing in a station: that station is where it is, not its next stop.
    when (val progress = (state.status as? TrainStatus.Live)?.progress) {
        TrainProgress.NotStarted -> rows.firstOrNull { it.activeIndex == 0 }?.let {
            Text("Départ de ${it.name} à ${clock(it.estimated ?: it.planned)}")
        }
        is TrainProgress.AtStop -> rows.firstOrNull { it.activeIndex == progress.index }?.let {
            Text("À ${it.name} · départ à ${clock(it.estimated ?: it.planned)}")
        }
        else -> next?.takeUnless { it.isAlighting }?.let { Text("Prochain arrêt : ${it.name} ${clock(it.estimated ?: it.planned)}") }
    }
    // Before boarding, both of the traveller's stations; after, only where they get off.
    val boarding = rows.firstOrNull { it.isBoarding }
    val alighting = rows.lastOrNull { it.isAlighting }
    if (boarding != null && boarding.status != StopStatus.PASSED) OwnStop("Montée", boarding, state.nowSeconds)
    alighting?.let { OwnStop("Descente", it, state.nowSeconds) }
}

@Composable
private fun OwnStop(label: String, row: StopRow, nowSeconds: Int) {
    val time = row.estimated ?: row.planned
    val delay = if (row.estimated != null && row.planned != null) (row.estimated - row.planned) / 60 else 0
    val minutes = time?.let { (it - nowSeconds) / 60 }
    val detail = when {
        row.status == StopStatus.REMOVED -> null
        row.status == StopStatus.PASSED -> " · déjà passé"
        minutes != null && minutes > 0 -> " · dans ${formatMinutes(minutes)}"
        else -> ""
    }
    if (detail == null) {
        Text("$label : ${row.name} · arrêt supprimé", color = MaterialTheme.colorScheme.error)
    } else {
        Text(stopText("$label : ${row.name}", clock(row.planned), row.estimated?.let(::clock), delay, detail))
    }
}

private fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"

private fun clock(seconds: Int?): String =
    seconds?.let { "%02d:%02d".format((it / 3600) % 24, (it / 60) % 60) }.orEmpty()

@Composable
private fun InfoPanel(
    state: TrainMapState,
    rows: List<StopRow>,
    trainPosition: Double?,
    showStops: Boolean,
    onShowStops: (Boolean) -> Unit,
    onTrainSelected: () -> Unit,
    onStopSelected: (StopRow) -> Unit,
) {
    val hasStops = rows.isNotEmpty()
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = if (hasStops) 0.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A handle, as on a sheet: a tap or a swipe opens and closes the stop list.
        if (hasStops) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable { onShowStops(!showStops) }
                    .pointerInput(showStops) {
                        detectVerticalDragGestures { _, drag ->
                            if (drag < -6 && !showStops) onShowStops(true)
                            if (drag > 6 && showStops) onShowStops(false)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
            }
        }
        when (val status = state.status) {
            TrainStatus.Loading -> Text("Chargement de la position…")
            is TrainStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
            TrainStatus.NotTracked -> Text(
                "Ce train n'est pas dans le suivi en direct pour l'instant, et son parcours prévu n'est pas connu.",
            )
            TrainStatus.Cancelled -> Text("Train supprimé.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            TrainStatus.Stale -> Text("Données en direct indisponibles : position non affichée.", color = MaterialTheme.colorScheme.error)
            // The train's own lines: a tap shows it on the map, with its bubble.
            is TrainStatus.Live -> Column(
                Modifier.fillMaxWidth().clickable(enabled = state.position != null, onClick = onTrainSelected),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LiveInfo(state, status.progress)
            }
        }
        state.train?.serviceNotes()?.forEach { note ->
            Text("⚠ $note", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        TrainAlertsLine(state)
        state.collectedAt?.takeIf { state.status is TrainStatus.Live }?.let {
            val time = it.atZoneSameInstant(PARIS).format(CLOCK)
            Text(
                if (state.train?.hasRealtime == true) "Position estimée · relevé $time" else "Position estimée d'après les horaires prévus",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hasStops) {
            if (showStops) {
                StopList(rows, trainPosition, state.alerts, onStopSelected)
            } else {
                TextButton(onClick = { onShowStops(true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Voir tous les arrêts ▲")
                }
            }
        }
    }
}

@Composable
private fun TrainAlertsLine(state: TrainMapState) {
    val alerts = state.alerts.train
    var open by rememberSaveable { mutableStateOf(false) }
    if (alerts.isNotEmpty()) {
        val count = if (alerts.size == 1) "1 information sur ce train" else "${alerts.size} informations sur ce train"
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text("⚠ $count ${if (open) "▲" else "▼"}", color = MaterialTheme.colorScheme.error)
        }
        if (open) {
            val maxHeight = (LocalConfiguration.current.screenHeightDp / 3).dp
            Column(
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AlertTexts(alerts)
            }
        }
    } else if (state.alertsFailed) {
        Text("Informations de perturbation indisponibles pour le moment.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlertTexts(alerts: List<ServiceAlert>) {
    alerts.forEach { alert ->
        Text("⚠ ${alert.title}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        if (alert.description.isNotEmpty()) Text(alert.description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StopList(rows: List<StopRow>, trainPosition: Double?, alerts: TrainAlerts, onSelected: (StopRow) -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    val listState = rememberLazyListState()
    // On opening, a long list starts near the train.
    LaunchedEffect(Unit) {
        trainPosition?.let { listState.scrollToItem((it.roundToInt() - 1).coerceAtLeast(0)) }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        itemsIndexed(rows, key = { _, row -> "${row.uic}-${row.planned}" }) { index, row ->
            val faded = row.status == StopStatus.PASSED || row.status == StopStatus.REMOVED
            val color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            val decoration = if (row.status == StopStatus.REMOVED) TextDecoration.LineThrough else null
            // The train's icon overflows its row: that row must be drawn above its neighbours.
            val holdsTrain = trainPosition?.roundToInt() == index
            Row(
                Modifier
                    .zIndex(if (holdsTrain) 1f else 0f)
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clickable(enabled = row.activeIndex != null) { onSelected(row) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StopTimeline(
                    index = index,
                    count = rows.size,
                    removed = row.status == StopStatus.REMOVED,
                    trainPosition = trainPosition,
                    modifier = Modifier.width(28.dp).fillMaxHeight(),
                )
                Text(
                    row.name,
                    color = color,
                    fontWeight = if (row.isBoarding || row.isAlighting || row.status == StopStatus.NEXT) FontWeight.SemiBold else null,
                    textDecoration = decoration,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                if (alerts.byStation[row.uic] != null) Text("⚠", color = MaterialTheme.colorScheme.error)
                Text(clock(row.planned), color = color, textDecoration = if (row.estimated != null) TextDecoration.LineThrough else decoration)
                row.estimated?.let { Text(clock(it), color = delayColor(), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** One row's piece of the vertical line, with its stop and, when it is there, the train. */
@Composable
private fun StopTimeline(index: Int, count: Int, removed: Boolean, trainPosition: Double?, modifier: Modifier) {
    val trainIcon = painterResource(R.drawable.ic_train)
    val background = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val top = if (index == 0) centerY else 0f
        val bottom = if (index == count - 1) centerY else size.height
        val lineWidth = 3.dp.toPx()
        val split = trainPosition?.let { (centerY + (it - index) * size.height).toFloat().coerceIn(top, bottom) } ?: top
        drawLine(ROUTE_PASSED, Offset(centerX, top), Offset(centerX, split), lineWidth)
        drawLine(ROUTE_AHEAD, Offset(centerX, split), Offset(centerX, bottom), lineWidth)

        val stopColor = if (removed || centerY <= split) ROUTE_PASSED else ROUTE_AHEAD
        val radius = (if (removed) 3.dp else 5.dp).toPx()
        drawCircle(background, radius, Offset(centerX, centerY))
        drawCircle(stopColor, radius, Offset(centerX, centerY), style = Stroke(2.dp.toPx()))

        if (trainPosition != null && trainPosition.roundToInt() == index) {
            val center = Offset(centerX, (centerY + (trainPosition - index) * size.height).toFloat())
            drawCircle(TRAIN_RED, 12.dp.toPx(), center)
            drawCircle(ComposeColor.White, 12.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            val iconSize = 16.dp.toPx()
            translate(center.x - iconSize / 2, center.y - iconSize / 2) {
                with(trainIcon) { draw(Size(iconSize, iconSize)) }
            }
        }
    }
}

@Composable
private fun LiveInfo(state: TrainMapState, progress: TrainProgress) {
    val route = state.route ?: return
    val stops = state.stops
    fun name(i: Int) = route.stops.getOrNull(i)?.name.orEmpty()

    // The delay is named after its station: the train's next one, which may not be the traveller's.
    val (headline, relevantDelay, at) = when (progress) {
        TrainProgress.NotStarted ->
            Triple("Départ de ${name(0)} à ${clock(stops.firstOrNull()?.estimatedDeparture)}", stops.firstOrNull()?.departureDelaySeconds, name(0))
        is TrainProgress.AtStop ->
            Triple(
                "À ${name(progress.index)} · départ à ${clock(stops.getOrNull(progress.index)?.estimatedDeparture)}",
                stops.getOrNull(progress.index)?.departureDelaySeconds, name(progress.index),
            )
        is TrainProgress.Between ->
            Triple(
                "Prochain arrêt : ${name(progress.to)} à ${clock(stops.getOrNull(progress.to)?.estimatedArrival)}",
                stops.getOrNull(progress.to)?.arrivalDelaySeconds, name(progress.to),
            )
        TrainProgress.Arrived ->
            Triple("Arrivé à ${name(stops.lastIndex)}", stops.lastOrNull()?.arrivalDelaySeconds, name(stops.lastIndex))
    }
    Text(headline, fontWeight = FontWeight.SemiBold)
    if (state.train?.hasRealtime == true) {
        val minutes = (relevantDelay ?: 0) / 60
        if (minutes > 0) {
            Text("Retard : +$minutes min à $at", color = delayColor())
        } else {
            Text("À l'heure")
        }
    }
    if (state.position == null) {
        Text("Position inconnue sur cette portion : les coordonnées d'une gare manquent.")
    } else if (progress is TrainProgress.Between && route.segments.getOrNull(progress.from)?.isRealTrack == false) {
        Text("Tracé des voies inconnu ici : ligne droite entre les gares.", style = MaterialTheme.typography.bodySmall)
    }
}
