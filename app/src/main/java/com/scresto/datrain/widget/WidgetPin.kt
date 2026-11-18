package com.scresto.datrain.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager

/** Android asks the user to place the widget on the home screen, when the launcher allows it. */
object WidgetPin {
    fun isSupported(context: Context): Boolean = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    suspend fun request(context: Context): Boolean =
        GlanceAppWidgetManager(context).requestPinGlanceAppWidget(DatrainWidgetReceiver::class.java)
}
