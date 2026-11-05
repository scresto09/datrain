package com.scresto.datrain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scresto.datrain.data.TimetableUpdateWorker
import com.scresto.datrain.ui.DatrainApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TimetableUpdateWorker.schedule(this)
        setContent { DatrainApp() }
    }
}
