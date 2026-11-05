package com.scresto.datrain

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scresto.datrain.data.TimetableUpdateWorker
import com.scresto.datrain.ui.DatrainApp

class MainActivity : ComponentActivity() {
    private var openedFavorite by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedFavorite = intent.getStringExtra(EXTRA_FAVORITE)
    }

    companion object {
        const val EXTRA_FAVORITE = "favorite"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TimetableUpdateWorker.schedule(this)
        openedFavorite = intent.getStringExtra(EXTRA_FAVORITE)
        setContent { DatrainApp(openedFavorite, onFavoriteOpened = { openedFavorite = null }) }
    }
}
