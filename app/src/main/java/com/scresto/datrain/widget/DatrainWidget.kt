package com.scresto.datrain.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.scresto.datrain.MainActivity
import com.scresto.datrain.data.FavoriteWatchWorker
import com.scresto.datrain.data.RecentHistoryStore

/** The next favorite train on the home screen, so that a glance is enough before leaving. */
class DatrainWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val content = WidgetData.load(context)
        provideContent { Content(context, content) }
    }

    companion object {
        fun isPlaced(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, DatrainWidgetReceiver::class.java))
                .isNotEmpty()

        suspend fun refresh(context: Context) {
            if (isPlaced(context)) DatrainWidget().updateAll(context)
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

private val BACKGROUND = ColorProvider(day = Color(0xFFFDF8FD), night = Color(0xFF211F26))
private val INK = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1E5))
private val MUTED = ColorProvider(day = Color(0xFF5D5864), night = Color(0xFFAAA5B0))
private val DELAY = ColorProvider(day = Color(0xFFE65100), night = Color(0xFFFF8A3D))
private val RED = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFEF6C6C))

@Composable
private fun Content(context: Context, content: WidgetContent) {
    val open = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (content is WidgetContent.Next) open.putExtra(MainActivity.EXTRA_FAVORITE, content.favoriteKey)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(BACKGROUND)
            .cornerRadius(16.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(actionStartActivity(open)),
    ) {
        when (content) {
            WidgetContent.NoFavorite -> Text(
                "Pas encore de favoris. Appuyez sur ☆ sur un train dans Datrain.",
                style = TextStyle(color = MUTED, fontSize = 13.sp),
            )
            WidgetContent.NoTimetable -> Text(
                "Horaires indisponibles : ouvrez Datrain.",
                style = TextStyle(color = MUTED, fontSize = 13.sp),
            )
            is WidgetContent.Next -> Next(content)
        }
    }
}

@Composable
private fun Next(next: WidgetContent.Next) {
    Row {
        Text(next.title, style = TextStyle(color = INK, fontWeight = FontWeight.Bold, fontSize = 14.sp))
        if (next.status == WidgetStatus.DELAYED) {
            Text(" · ${if (next.delayMinutes > 0) "+" else ""}${next.delayMinutes} min", style = TextStyle(color = DELAY, fontWeight = FontWeight.Bold, fontSize = 14.sp))
        }
    }
    if (next.estimated != null) {
        Text(next.planned, style = TextStyle(color = MUTED, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough))
        Text(next.estimated, style = TextStyle(color = DELAY, fontWeight = FontWeight.Medium, fontSize = 13.sp))
    } else if (next.status == WidgetStatus.CANCELLED) {
        Text(next.planned, style = TextStyle(color = MUTED, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough))
    } else {
        Text(next.planned, style = TextStyle(color = INK, fontSize = 13.sp))
    }
    when (next.status) {
        WidgetStatus.CANCELLED -> Text("Supprimé", style = TextStyle(color = RED, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        WidgetStatus.CONNECTION_AT_RISK -> Text("Correspondance menacée", style = TextStyle(color = RED, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        WidgetStatus.ON_TIME -> Text("À l'heure", style = TextStyle(color = MUTED, fontSize = 12.sp))
        WidgetStatus.DELAYED, WidgetStatus.PLANNED -> Unit
    }
}
