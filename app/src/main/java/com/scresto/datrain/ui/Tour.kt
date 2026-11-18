package com.scresto.datrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.scresto.datrain.data.TimetableState
import kotlin.math.roundToInt

/** What the guided tour points at, by name, as laid out on the screen. */
internal object TourTargets {
    /** No element: the bubble alone, in the middle. */
    const val WELCOME = "welcome"
    const val STATIONS = "stations"
    const val DATETIME = "datetime"
    const val RECENT_JOURNEYS = "recent-journeys"
    const val RECENT_TRAINS = "recent-trains"
    const val SEARCH = "search"
    const val RESULT = "result"
    const val STAR = "star"
    const val MAP = "map"
    const val PANEL = "panel"
    const val STATION = "station"
    /** The station's own bubble, lit with it. */
    const val STATION_INFO = "station-info"
    const val STOPS = "stops"
    const val NUMBER = "number"
    const val LOGO = "logo"

    val bounds = mutableStateMapOf<String, Rect>()
}

/** The element the tour points at now, if any. */
internal val LocalTourTarget = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** Marks an element the tour can point at; scrolled into view when its turn comes. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.tourTarget(name: String): Modifier = composed {
    val requester = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val size = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Size.Zero) }
    val room = with(LocalDensity.current) { 160.dp.toPx() }
    val active = LocalTourTarget.current == name
    LaunchedEffect(active) {
        if (active) {
            // Once a card it opens is laid out; with room below, clear of the navigation bar.
            kotlinx.coroutines.delay(300)
            requester.bringIntoView(Rect(0f, 0f, size.value.width, size.value.height + room))
        }
    }
    bringIntoViewRequester(requester)
        .onGloballyPositioned {
            size.value = Size(it.size.width.toFloat(), it.size.height.toFloat())
            TourTargets.bounds[name] = it.boundsInRoot()
        }
}

/** [target]: what the finger points at; [alsoLit]: shown along with it, such as what it opened. */
internal class TourStep(val target: String, val text: String, val alsoLit: List<String> = emptyList())

/** The steps, in order; the view model moves the screens along with them. */
internal val TOUR = listOf(
    TourStep(
        TourTargets.WELCOME,
        "Bienvenue dans Datrain !\nVoici une courte présentation des principales fonctionnalités. Voulez-vous la découvrir ?\n" +
            "Vous trouverez également plus de détails dans l'aide en ligne, accessible en touchant le logo Datrain en haut à gauche.",
    ),
    TourStep(TourTargets.STATIONS, "Indiquez vos gares de départ et d'arrivée. Pour cet exemple : Toulouse → Carbonne."),
    TourStep(TourTargets.RECENT_JOURNEYS, "Vos dernières recherches apparaissent ici. Touchez-en une pour la relancer."),
    TourStep(TourTargets.DATETIME, "Choisissez la date et l'heure de départ, ou l'heure d'arrivée souhaitée."),
    TourStep(TourTargets.SEARCH, "Lancez ensuite la recherche."),
    TourStep(
        TourTargets.RESULT,
        "Les résultats indiquent les horaires, les retards et les suppressions. Touchez un trajet pour en voir le détail.",
    ),
    TourStep(TourTargets.STAR, "Touchez ☆ pour suivre ce train : tous les jours, certains jours ou pour ce trajet uniquement."),
    TourStep(TourTargets.MAP, "« Voir sur la carte » permet de suivre le train en temps réel."),
    TourStep(
        TourTargets.STATION,
        "Touchez une gare sur la carte pour consulter ses horaires, les retards et les voies. Ici, la gare de départ.",
        alsoLit = listOf(TourTargets.STATION_INFO),
    ),
    TourStep(TourTargets.PANEL, "Glissez ce panneau vers le haut pour consulter tous les arrêts et les messages d'information."),
    TourStep(
        TourTargets.STOPS,
        "Voici la liste des arrêts, avec les horaires, les retards et les voies. Touchez un arrêt pour le localiser sur la carte.",
    ),
    TourStep(TourTargets.NUMBER, "Vous pouvez aussi rechercher un train par son numéro en utilisant #."),
    TourStep(TourTargets.RECENT_TRAINS, "Retrouvez ici les derniers trains que vous avez consultés."),
    TourStep(
        TourTargets.LOGO,
        "Touchez le logo pour accéder aux réglages, revoir la visite guidée ou consulter l'aide en ligne.\n" +
            "Vous voilà prêt à embarquer ! Bon voyage 🚆 😉",
    ),
)

/**
 * Over the real screens: everything dimmed but the element pointed at, a finger on it, and a bubble saying
 * what it is for. The screens below cannot be touched meanwhile; "Passer" or back ends it.
 */
@Composable
internal fun TourOverlay(state: UiState, vm: SearchViewModel) {
    val step = state.tour ?: return
    BackHandler(onBack = vm::endTour)
    val ready = state.timetable is TimetableState.Ready
    LaunchedEffect(ready, step) { if (ready && step <= 1) vm.prepareTour() }
    // Nothing to show at this step (no train today, or not trackable): on to the next one that has something.
    val welcome = TOUR[step].target == TourTargets.WELCOME
    // The welcome needs nothing loaded: it can be answered at once.
    val waiting = !welcome && (!ready || state.isLoading)
    LaunchedEffect(step, state.isLoading, state.itineraries.isEmpty(), state.trackedTrip) {
        val onResults = TOUR[step].target in
            setOf(TourTargets.RESULT, TourTargets.STAR, TourTargets.MAP, TourTargets.STATION, TourTargets.PANEL, TourTargets.STOPS)
        if (!waiting && onResults && state.itineraries.isEmpty()) vm.tourGoTo(TOUR.indexOfFirst { it.target == TourTargets.NUMBER })
    }
    val tourStep = TOUR[step]
    val target = TourTargets.bounds[tourStep.target]?.takeUnless { waiting }
    // What is lit: the element, and what it opened.
    val lit = target?.let { main ->
        tourStep.alsoLit.mapNotNull { TourTargets.bounds[it] }.fold(main) { all, other ->
            Rect(minOf(all.left, other.left), minOf(all.top, other.top), maxOf(all.right, other.right), maxOf(all.bottom, other.bottom))
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
    ) {
        val margin = with(density) { 6.dp.toPx() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.6f))
            lit?.let {
                drawRoundRect(
                    Color.Black,
                    topLeft = Offset(it.left - margin, it.top - margin),
                    size = Size(it.width + margin * 2, it.height + margin * 2),
                    cornerRadius = CornerRadius(margin * 2),
                    blendMode = BlendMode.Clear,
                )
            }
        }
        val height = with(density) { maxHeight.toPx() }
        // The bubble goes where there is room for it, finger included; over the bottom of a large element otherwise.
        val gap = with(density) { 64.dp.toPx() }
        val need = with(density) { 220.dp.toPx() } + gap
        val roomBelow = lit?.let { height - it.bottom } ?: 0f
        val roomAbove = lit?.top ?: 0f
        val side = when {
            lit == null -> Side.MIDDLE
            roomBelow >= need && (lit.center.y < height / 2 || roomAbove < need) -> Side.BELOW
            roomAbove >= need -> Side.ABOVE
            else -> Side.OVER
        }
        target?.let { Finger(it, below = side == Side.BELOW) }
        val bubbleTop = when (side) {
            Side.MIDDLE -> height / 3
            Side.BELOW -> lit!!.bottom + gap
            else -> null
        }
        val bubbleBottom = when (side) {
            Side.ABOVE -> height - lit!!.top + gap
            Side.OVER -> with(density) { 72.dp.toPx() }
            else -> null
        }
        Box(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentAlignment = if (bubbleTop != null) Alignment.TopCenter else Alignment.BottomCenter,
        ) {
            Bubble(
                text = if (waiting) (if (ready) "Recherche…" else "Chargement des horaires…") else tourStep.text,
                welcome = welcome,
                last = step == TOUR.lastIndex,
                enabled = !waiting,
                onSkip = vm::endTour,
                onNext = vm::tourNext,
                modifier = Modifier.offset {
                    IntOffset(0, bubbleTop?.roundToInt() ?: -(bubbleBottom ?: 0f).roundToInt())
                },
            )
        }
    }
}

private enum class Side { MIDDLE, BELOW, ABOVE, OVER }

/** A finger tapping the element: below it pointing up, or above it pointing down. */
@Composable
private fun Finger(target: Rect, below: Boolean) {
    val bounce by rememberInfiniteTransition(label = "finger").animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "bounce",
    )
    val density = LocalDensity.current
    val size = with(density) { 40.dp.toPx() }
    val travel = with(density) { 10.dp.toPx() } * bounce
    val x = target.center.x - size / 2
    val y = if (below) target.bottom + travel else target.top - size - travel
    Text(
        if (below) "👆" else "👇",
        fontSize = 34.sp,
        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
    )
}

@Composable
private fun Bubble(text: String, welcome: Boolean, last: Boolean, enabled: Boolean, onSkip: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (welcome) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { DatrainLogo() }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!last) TextButton(onClick = onSkip) { Text(if (welcome) "Non merci" else "Passer") }
                Button(onClick = onNext, enabled = enabled || last) {
                    Text(
                        when {
                            welcome -> "Commencer"
                            last -> "C'est parti"
                            else -> "Suivant"
                        },
                    )
                }
            }
        }
    }
}
