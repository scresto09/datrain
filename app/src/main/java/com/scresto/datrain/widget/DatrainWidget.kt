package com.scresto.datrain.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.Visibility
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.visibility
import androidx.work.ExistingWorkPolicy
import com.scresto.datrain.MainActivity
import com.scresto.datrain.R
import com.scresto.datrain.data.DepartureAlarms
import com.scresto.datrain.data.FavoriteWatchStore
import com.scresto.datrain.data.FavoriteWatchWorker
import com.scresto.datrain.data.RecentHistoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The next favorite train on the home screen, so that a glance is enough before leaving. */
class DatrainWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = WidgetData.load(context)
        provideContent {
            // An open widget is only recomposed on update, not built again: each update bumps this
            // version, and the data is read again for it, instead of showing what was loaded first.
            val version = currentState(VERSION) ?: 0L
            var content by remember { mutableStateOf(first) }
            LaunchedEffect(version) { content = WidgetData.load(context) }
            Content(context, content)
        }
    }

    companion object {
        fun isPlaced(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, DatrainWidgetReceiver::class.java))
                .isNotEmpty()

        private val VERSION = longPreferencesKey("version")

        suspend fun refresh(context: Context) {
            if (!isPlaced(context)) return
            GlanceAppWidgetManager(context).getGlanceIds(DatrainWidget::class.java).forEach { redraw(context, it) }
        }

        /** Makes the widget read its data again, even while still open. */
        suspend fun redraw(context: Context, id: GlanceId) {
            updateAppWidgetState(context, id) { it[VERSION] = System.nanoTime() }
            DatrainWidget().update(context, id)
        }
    }
}

class DatrainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DatrainWidget()

    /** Placing the first widget starts the background check, removing the last one may stop it. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        FavoriteWatchWorker.sync(context, RecentHistoryStore(context).load())
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        FavoriteWatchWorker.sync(context, RecentHistoryStore(context).load())
    }
}

// Smoked glass: a dark veil over the wallpaper, readable on any of them, in both themes.
private val GLASS = ColorProvider(day = Color(0x991C1B1F), night = Color(0x991C1B1F))
private val WHITE = ColorProvider(day = Color.White, night = Color.White)
private val SOFT = ColorProvider(day = Color(0xB8FFFFFF), night = Color(0xB8FFFFFF))
private val FAINT = ColorProvider(day = Color(0x80FFFFFF), night = Color(0x80FFFFFF))
private val PILL_NEUTRAL = ColorProvider(day = Color(0x33FFFFFF), night = Color(0x33FFFFFF))
private val PILL_DELAY = ColorProvider(day = Color(0xFFE65100), night = Color(0xFFE65100))
private val PILL_RED = ColorProvider(day = Color(0xFFD32F2F), night = Color(0xFFD32F2F))

@Composable
private fun Content(context: Context, content: WidgetContent) {
    val open = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (content is WidgetContent.Next) open.putExtra(MainActivity.EXTRA_FAVORITE, content.favoriteKey)
    // No tap on the whole widget: two side by side areas, each reaching the edges, so that a tap near the
    // refresh never falls on the area that opens the map.
    Row(
        GlanceModifier.fillMaxSize().background(GLASS).cornerRadius(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val openArea = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(open))
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
        when (content) {
            WidgetContent.NoFavorite -> Message(openArea, "Pas encore de favoris. Appuyez sur ☆ sur un train dans Datrain.")
            WidgetContent.NoTimetable -> Message(openArea, "Horaires indisponibles : ouvrez Datrain.")
            is WidgetContent.Next -> Next(content, openArea)
        }
    }
}

/** The logo, in white with its red dot: the word and the dot are two drawings laid on each other. */
@Composable
private fun Logo() {
    Box(GlanceModifier.size(width = 53.dp, height = 13.dp)) {
        Image(
            ImageProvider(R.drawable.logo_word),
            contentDescription = "Datrain",
            colorFilter = ColorFilter.tint(WHITE),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Image(ImageProvider(R.drawable.logo_dot), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
    }
}

@Composable
private fun Message(modifier: GlanceModifier, text: String) {
    Column(modifier.padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo()
        Text(text, style = TextStyle(color = SOFT, fontSize = 13.sp))
    }
}

@Composable
private fun RowScope.Next(next: WidgetContent.Next, openArea: GlanceModifier) {
    Column(openArea, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Text(" · ${next.title}", style = TextStyle(color = SOFT, fontSize = 12.sp), maxLines = 1)
        }
        val big = TextStyle(color = WHITE, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (next.newDeparture != null || next.status == WidgetStatus.CANCELLED) {
                Text(next.departure, style = big.copy(color = FAINT, fontWeight = FontWeight.Normal, textDecoration = TextDecoration.LineThrough))
            }
            next.newDeparture?.let { Text(" $it", style = big) }
            if (next.newDeparture == null && next.status != WidgetStatus.CANCELLED) Text(next.departure, style = big)
            // By the time rather than at the end of the route, where long station names would cut it off.
            next.platform?.takeIf { next.status != WidgetStatus.CANCELLED }?.let {
                Text("  voie $it", style = TextStyle(color = WHITE, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
        Text(next.route, style = TextStyle(color = SOFT, fontSize = 13.sp), maxLines = 1)
    }
    val (label, color) = when (next.status) {
        WidgetStatus.ON_TIME -> "À l'heure" to PILL_NEUTRAL
        WidgetStatus.DELAYED -> "${if (next.delayMinutes > 0) "+" else ""}${next.delayMinutes} min" to PILL_DELAY
        WidgetStatus.CANCELLED -> "Supprimé" to PILL_RED
        WidgetStatus.CONNECTION_AT_RISK -> "Correspondance" to PILL_RED
        WidgetStatus.PLANNED -> null to null
    }
    // The whole right-hand strip, top to bottom, is the refresh button: a small arrow alone is easy to miss.
    // The rest of the widget opens the map.
    Column(
        GlanceModifier.fillMaxHeight().clickable(actionRunCallback<RefreshAction>()).padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null && color != null) {
            Box(GlanceModifier.background(color).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(label, style = TextStyle(color = WHITE, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
        }
        // The time of the last check sits by the refresh arrow, which says what it is. While a refresh
        // is on its way both vanish, keeping their room so that nothing else moves, then come back.
        val refreshing = next.checkedAt == WidgetData.REFRESHING
        Row(
            GlanceModifier.padding(top = 2.dp).visibility(if (refreshing) Visibility.Invisible else Visibility.Visible),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            next.checkedAt?.let { Text(it, style = TextStyle(color = FAINT, fontSize = 11.sp)) }
            Image(
                ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Actualiser",
                colorFilter = ColorFilter.tint(SOFT),
                modifier = GlanceModifier.size(26.dp).padding(4.dp),
            )
        }
    }
}

// A widget tap is handled in a broadcast, which Android stops after about ten seconds.
private const val REFRESH_TIMEOUT_MILLIS = 8_000L
// A little over the second during which the store says "refreshing", so that the time then shows.
private const val MIN_REFRESHING_MILLIS = 1_100L

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // At once "…", so that the tap visibly did something. The check then runs right here: queued,
        // Android could hold it back for minutes. Should it take too long, the queue takes over.
        val start = System.currentTimeMillis()
        FavoriteWatchStore(context).markRefreshAsked()
        DatrainWidget.redraw(context, glanceId)
        val done = withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { FavoriteWatchWorker.check(context) }
        val took = System.currentTimeMillis() - start
        if (done == null) {
            DepartureAlarms.runNow(context, ExistingWorkPolicy.KEEP)
        } else if (took < MIN_REFRESHING_MILLIS) {
            // A check with nothing to read takes a tenth of a second: "…" would not even be seen.
            delay(MIN_REFRESHING_MILLIS - took)
            DatrainWidget.redraw(context, glanceId)
        }
    }
}
