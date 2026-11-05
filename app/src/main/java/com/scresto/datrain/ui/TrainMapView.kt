package com.scresto.datrain.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scresto.datrain.R
import com.scresto.datrain.data.LatLon
import com.scresto.datrain.data.TrainProgress
import com.scresto.datrain.data.TrainRoute
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

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val TAP_RADIUS = 36f

@Composable
internal fun TrainMap(
    controller: TrainMapController,
    route: TrainRoute?,
    progress: TrainProgress?,
    position: LatLon?,
    others: List<TrainRoute>,
    changes: List<LatLon>,
    modifier: Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = controller.mapView

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
    LaunchedEffect(route, progress, position, others, changes) { controller.show(route, progress, position, others, changes) }
}

internal class TrainMapController(context: Context, val mapView: MapView) {
    /** Called with the tapped train or station, or null for empty map. */
    var onSelected: (String?) -> Unit = {}
    var cameraMoves by mutableIntStateOf(0)
        private set
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var route: TrainRoute? = null
    private var progress: TrainProgress? = null
    private var position: LatLon? = null
    private var framedRoute: TrainRoute? = null
    private var drawnPassedCount = -1
    private var drawnRoute: TrainRoute? = null
    private var others: List<TrainRoute> = emptyList()
    private var changes: List<LatLon> = emptyList()
    private var drawnOthers: List<TrainRoute>? = null
    private var framedOthers = 0

    private val otherSource = GeoJsonSource("others")
    private val changeSource = GeoJsonSource("changes")
    private val routeSource = GeoJsonSource("route")
    private val stopSource = GeoJsonSource("stops")
    private val trainSource = GeoJsonSource("train")

    init {
        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.addOnCameraMoveListener { cameraMoves++ }
            loaded.addOnCameraIdleListener { cameraMoves++ }
            loaded.addOnMapClickListener { point ->
                onSelected(featureAt(loaded, point))
                true
            }
            loaded.setStyle(STYLE_URL) { ready ->
                addLayers(context, ready)
                style = ready
                render()
            }
        }
    }

    fun show(route: TrainRoute?, progress: TrainProgress?, position: LatLon?, others: List<TrainRoute>, changes: List<LatLon>) {
        this.route = route
        this.progress = progress
        this.position = position
        this.others = others
        this.changes = changes
        render()
    }

    fun screenPoint(position: LatLon): PointF? =
        map?.projection?.toScreenLocation(LatLng(position.lat, position.lon))

    /** The map has changed size: the whole route must fit again. */
    fun frameRoute() {
        route?.let(::frame)
    }

    fun focus(position: LatLon) {
        map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(position.lat, position.lon)))
    }

    private fun featureAt(loaded: MapLibreMap, point: LatLng): String? {
        val screen = loaded.projection.toScreenLocation(point)
        val area = RectF(screen.x - TAP_RADIUS, screen.y - TAP_RADIUS, screen.x + TAP_RADIUS, screen.y + TAP_RADIUS)
        if (loaded.queryRenderedFeatures(area, "train-background").isNotEmpty()) return SELECTED_TRAIN
        val index = loaded.queryRenderedFeatures(area, "stops").firstOrNull()?.getNumberProperty("index")?.toInt()
        return index?.let { route?.stops?.getOrNull(it)?.uic }
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
        style.addSource(otherSource)
        style.addSource(changeSource)
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

        // The journey's other trains: pale, under the followed one.
        style.addLayer(
            LineLayer("others", "others").withProperties(
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineColor(blue),
                PropertyFactory.lineOpacity(0.35f),
            ),
        )

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
            CircleLayer("changes", "changes").withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(Color.parseColor("#1C1B1F")),
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
        if (others !== drawnOthers) {
            drawnOthers = others
            otherSource.setGeoJson(
                FeatureCollection.fromFeatures(
                    others.flatMap { it.segments.filterNotNull() }.map { segment ->
                        Feature.fromGeometry(LineString.fromLngLats(segment.points.map { Point.fromLngLat(it.lon, it.lat) }))
                    },
                ),
            )
        }
        changeSource.setGeoJson(FeatureCollection.fromFeatures(changes.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        // Framed again when the other trains of the journey arrive, so that all of it shows.
        if (currentRoute != null && (framedRoute !== currentRoute || framedOthers != others.size)) {
            framedRoute = currentRoute
            framedOthers = others.size
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
                        addNumberProperty("index", i)
                    }
                }
            },
        )

    private fun frame(route: TrainRoute) {
        val points = (listOf(route) + others).flatMap { r -> r.segments.filterNotNull().flatMap { it.points } + r.stops.mapNotNull { it.position } }
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
    }
}
