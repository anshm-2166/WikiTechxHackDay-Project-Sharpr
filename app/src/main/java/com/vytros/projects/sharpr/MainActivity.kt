package com.vytros.projects.sharpr

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.ui.AppRoot
import com.vytros.projects.sharpr.ui.AppState
import com.vytros.projects.sharpr.ui.theme.SharprTheme
import com.vytros.projects.sharpr.ui.theme.isDarkTheme
import com.vytros.projects.sharpr.work.Notifications

class MainActivity : ComponentActivity() {
    /** Exposed for UI tests. */
    val state = AppState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as SharprApp).graph
        handleIntent(intent)
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            SharprTheme(settings) {
                AppRoot(graph, settings, state)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Notifications.appVisible = true
    }

    override fun onStop() {
        Notifications.appVisible = false
        super.onStop()
    }

    /** Notification taps and media shared to Sharpr from other apps. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_RECORD)?.let { state.openRecord(it) }
        if (intent.getStringExtra(Notifications.EXTRA_OPEN) == Notifications.OPEN_QUEUE) state.openQueue()
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val type = intent.type ?: return
            val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND) {
                listOfNotNull(streamExtra(intent))
            } else {
                streamListExtra(intent)
            }
            val kind = when {
                type.startsWith("image/") -> MediaType.Image
                type.startsWith("video/") -> MediaType.Video
                type.startsWith("audio/") -> MediaType.Audio
                else -> return
            }
            if (uris.isNotEmpty()) state.shared(kind, uris)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
