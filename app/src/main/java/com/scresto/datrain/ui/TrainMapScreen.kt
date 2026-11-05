package com.scresto.datrain.ui

import android.content.Context
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scresto.datrain.R
import com.scresto.datrain.data.LatLon
import com.scresto.datrain.data.LiveStop
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.TrainProgress
import com.scresto.datrain.data.TrainRoute
import com.scresto.datrain.data.Trip
import kotlinx.coroutines.Dispatchers
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.format.DateTimeFormatter

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainMapScreen(trip: Trip, onBack: () -> Unit, vm: TrainMapViewModel = viewModel()) {
    val state by vm.state.collectAsState(Dispatchers.Main.immediate)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    BackHandler(onBack = onBack)
    // Off screen, no network calls at all.
    LaunchedEffect(trip, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.follow(trip) }
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
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TrainMap(
                route = state.route,
                progress = (state.status as? TrainStatus.Live)?.progress,
                position = state.position,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                InfoPanel(state)
            }
        }
    }
}

@Composable
private fun InfoPanel(state: TrainMapState) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val status = state.status) {
            TrainStatus.Loading -> Text("Chargement de la position…")
            is TrainStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
            TrainStatus.NotTracked -> Text(
                "Ce train n'est pas dans le suivi en direct pour l'instant, et son parcours prévu n'est pas connu.",
            )
            TrainStatus.Cancelled -> Text("Train supprimé.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            TrainStatus.Stale -> Text("Données en direct indisponibles : position non affichée.", color = MaterialTheme.colorScheme.error)
            is TrainStatus.Live -> LiveInfo(state, status.progress)
        }
        state.collectedAt?.takeIf { state.status is TrainStatus.Live }?.let {
            val time = it.atZoneSameInstant(PARIS).format(CLOCK)
            Text(
                if (state.train?.hasRealtime == true) "Position estimée · relevé $time" else "Position estimée d'après les horaires prévus",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Données SNCF (ODbL), via Train Nomade · Île-de-France Mobilités · Voies © OpenStreetMap",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LiveInfo(state: TrainMapState, progress: TrainProgress) {
    val route = state.route ?: return
    val stops = state.stops
    fun name(i: Int) = route.stops.getOrNull(i)?.name.orEmpty()
    fun clock(seconds: Int?) = seconds?.let { "%02d:%02d".format((it / 3600) % 24, (it / 60) % 60) }.orEmpty()

    val (headline, relevantDelay) = when (progress) {
        TrainProgress.NotStarted ->
            "Départ de ${name(0)} à ${clock(stops.firstOrNull()?.estimatedDeparture)}" to stops.firstOrNull()?.departureDelaySeconds
        is TrainProgress.AtStop ->
            "À ${name(progress.index)} · départ à ${clock(stops.getOrNull(progress.index)?.estimatedDeparture)}" to
                stops.getOrNull(progress.index)?.departureDelaySeconds
        is TrainProgress.Between ->
            "Prochain arrêt : ${name(progress.to)} à ${clock(stops.getOrNull(progress.to)?.estimatedArrival)}" to
                stops.getOrNull(progress.to)?.arrivalDelaySeconds
        TrainProgress.Arrived ->
            "Arrivé à ${name(stops.lastIndex)}" to stops.lastOrNull()?.arrivalDelaySeconds
    }
    Text(headline, fontWeight = FontWeight.SemiBold)
    if (state.train?.hasRealtime == true) {
        val minutes = (relevantDelay ?: 0) / 60
        if (minutes > 0) {
            Text("Retard : +$minutes min", color = MaterialTheme.colorScheme.error)
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

@Composable
private fun TrainMap(route: TrainRoute?, progress: TrainProgress?, position: LatLon?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        // Must come before creating the view.
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    val controller = remember { TrainMapController(context, mapView) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
    LaunchedEffect(route, progress, position) { controller.show(route, progress, position) }
}

private class TrainMapController(context: Context, mapView: MapView) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var route: TrainRoute? = null
    private var progress: TrainProgress? = null
    private var position: LatLon? = null
    private var framedRoute: TrainRoute? = null
    private var drawnPassedCount = -1
    private var drawnRoute: TrainRoute? = null

    private val routeSource = GeoJsonSource("route")
    private val stopSource = GeoJsonSource("stops")
    private val trainSource = GeoJsonSource("train")

    init {
        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.setStyle(STYLE_URL) { ready ->
                addLayers(context, ready)
                style = ready
                render()
            }
        }
    }

    fun show(route: TrainRoute?, progress: TrainProgress?, position: LatLon?) {
        this.route = route
        this.progress = progress
        this.position = position
        render()
    }

    /** The style shows names in English ("Normandy"); road numbers stay as they are. */
    private fun useFrenchLabels(style: Style) {
        val french = Expression.coalesce(Expression.get("name:fr"), Expression.get("name"))
        style.layers.filterIsInstance<SymbolLayer>()
            .filter { it.textField.expression?.toString()?.contains("name") == true }
            .forEach { it.setProperties(PropertyFactory.textField(french)) }
    }

    private fun addLayers(context: Context, style: Style) {
        useFrenchLabels(style)
        style.addSource(routeSource)
        style.addSource(stopSource)
        style.addSource(trainSource)

        val passedOrAhead = { passed: Int, ahead: Int ->
            Expression.switchCase(
                Expression.toBool(Expression.get("passed")),
                Expression.color(passed),
                Expression.color(ahead),
            )
        }
        val grey = Color.parseColor("#8A8F98")
        val blue = Color.parseColor("#0B5FA5")

        style.addLayer(
            LineLayer("route-track", "route").withProperties(
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineColor(passedOrAhead(grey, blue)),
            ).withFilter(Expression.eq(Expression.get("real"), Expression.literal(true))),
        )
        // Dashed: straight line for lack of a track, not to be mistaken for the real one.
        style.addLayer(
            LineLayer("route-straight", "route").withProperties(
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineColor(passedOrAhead(grey, blue)),
            ).withFilter(Expression.eq(Expression.get("real"), Expression.literal(false))),
        )
        style.addLayer(
            CircleLayer("stops", "stops").withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(passedOrAhead(grey, blue)),
            ),
        )
        style.addLayer(
            CircleLayer("train-background", "train").withProperties(
                PropertyFactory.circleRadius(15f),
                PropertyFactory.circleColor(Color.parseColor("#C62828")),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            ),
        )
        style.addImage("train-icon", context.getDrawable(R.drawable.ic_train)!!)
        style.addLayer(
            SymbolLayer("train-icon", "train").withProperties(
                PropertyFactory.iconImage("train-icon"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    private fun render() {
        if (style == null) return
        val currentRoute = route
        val passedCount = progress?.let(::passedSegments) ?: 0

        if (currentRoute != null && (currentRoute !== drawnRoute || passedCount != drawnPassedCount)) {
            drawnRoute = currentRoute
            drawnPassedCount = passedCount
            routeSource.setGeoJson(routeFeatures(currentRoute, passedCount))
            stopSource.setGeoJson(stopFeatures(currentRoute, passedCount))
        }
        if (currentRoute != null && framedRoute !== currentRoute) {
            framedRoute = currentRoute
            frame(currentRoute)
        }
        trainSource.setGeoJson(
            position?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
                ?.let { FeatureCollection.fromFeature(it) }
                ?: FeatureCollection.fromFeatures(emptyList()),
        )
    }

    /** Number of fully travelled segments. */
    private fun passedSegments(progress: TrainProgress): Int = when (progress) {
        TrainProgress.NotStarted -> 0
        is TrainProgress.AtStop -> progress.index
        is TrainProgress.Between -> progress.from
        TrainProgress.Arrived -> Int.MAX_VALUE
    }

    private fun routeFeatures(route: TrainRoute, passed: Int): FeatureCollection =
        FeatureCollection.fromFeatures(
            route.segments.mapIndexedNotNull { i, segment ->
                segment?.let {
                    Feature.fromGeometry(LineString.fromLngLats(it.points.map { p -> Point.fromLngLat(p.lon, p.lat) })).apply {
                        addBooleanProperty("passed", i < passed)
                        addBooleanProperty("real", it.isRealTrack)
                    }
                }
            },
        )

    private fun stopFeatures(route: TrainRoute, passed: Int): FeatureCollection =
        FeatureCollection.fromFeatures(
            route.stops.mapIndexedNotNull { i, stop ->
                stop.position?.let {
                    Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
                        addBooleanProperty("passed", i < passed)
                    }
                }
            },
        )

    private fun frame(route: TrainRoute) {
        val points = route.segments.filterNotNull().flatMap { it.points } + route.stops.mapNotNull { it.position }
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
    }
}
