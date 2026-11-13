# Readable stack traces in crash reports; Play deobfuscates them with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Glance stores widget class names; keep them stable across versions.
-keep class com.scresto.datrain.widget.** extends androidx.glance.appwidget.GlanceAppWidget
-keep class com.scresto.datrain.widget.** extends androidx.glance.appwidget.GlanceAppWidgetReceiver
