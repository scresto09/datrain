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
import com.scresto.datrain.ui.DatrainRoot

class MainActivity : ComponentActivity() {
    private var openedFavorite by mutableStateOf<String?>(null)
    private var openedAlternatives by mutableStateOf<Pair<String, String>?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        openedFavorite = intent.getStringExtra(EXTRA_FAVORITE)
        openedAlternatives = intent.getStringExtra(EXTRA_ALTERNATIVES)?.let { key -> intent.getStringExtra(EXTRA_LEAVES)?.let { key to it } }
    }

    companion object {
        const val EXTRA_FAVORITE = "favorite"
        /** The "Autres trains" button of a cancellation: the favorite, and when its run was to leave. */
        const val EXTRA_ALTERNATIVES = "alternatives"
        const val EXTRA_LEAVES = "leaves"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TimetableUpdateWorker.schedule(this)
        read(intent)
        setContent {
            DatrainRoot(
                openedFavorite,
                onFavoriteOpened = { openedFavorite = null },
                openedAlternatives = openedAlternatives,
                onAlternativesOpened = { openedAlternatives = null },
            )
        }
    }
}
