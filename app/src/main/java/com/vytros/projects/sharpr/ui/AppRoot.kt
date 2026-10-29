package com.vytros.projects.sharpr.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vytros.projects.sharpr.Graph
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.Analyzer
import com.vytros.projects.sharpr.engine.media.MediaProbe
import com.vytros.projects.sharpr.ui.components.NavItem
import com.vytros.projects.sharpr.ui.components.NavPill
import com.vytros.projects.sharpr.ui.screens.AudioEditorScreen
import com.vytros.projects.sharpr.ui.screens.DetailScreen
import com.vytros.projects.sharpr.ui.screens.ImageEditorScreen
import com.vytros.projects.sharpr.ui.screens.LibraryScreen
import com.vytros.projects.sharpr.ui.screens.OnboardingScreen
import com.vytros.projects.sharpr.ui.screens.QueueScreen
import com.vytros.projects.sharpr.ui.screens.SettingsScreen
import com.vytros.projects.sharpr.ui.screens.StudioScreen
import com.vytros.projects.sharpr.ui.screens.VideoEditorScreen
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.work.JobEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What screens need from the outside world. */
class Actions(
    val graph: Graph,
    val state: AppState,
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val context: Context,
    val pickImages: () -> Unit,
    val pickVideo: () -> Unit,
    val pickAudio: () -> Unit,
    val askNotifications: () -> Unit,
) {
    val needsNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun runAsync(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message, withDismissAction = true) }
    }

    fun import(type: MediaType, uris: List<Uri>) {
        if (uris.isEmpty() || state.importing != null) return
        scope.launch {
            state.importing = "Opening…"
            try {
                when (type) {
                    MediaType.Image -> {
                        val picked = uris.take(MAX_BATCH)
                        val drafts = picked.mapIndexedNotNull { i, uri ->
                            if (picked.size > 1) state.importing = "Opening ${i + 1} of ${picked.size}…"
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val m = graph.library.import(uri, MediaType.Image)
                                    val a = Analyzer.analyze(m.file)
                                    if (a == null) {
                                        m.file.delete()
                                        null
                                    } else {
                                        ImageDraft(m, a)
                                    }
                                }.getOrNull()
                            }
                        }
                        if (drafts.isEmpty()) toast("Couldn't open that image.")
                        else {
                            if (drafts.size < picked.size) toast("${picked.size - drafts.size} file(s) couldn't be read and were skipped.")
                            if (uris.size > MAX_BATCH) toast("Batches are limited to $MAX_BATCH images.")
                            state.push(Screen.ImageEditor(drafts))
                        }
                    }
                    MediaType.Video -> {
                        state.importing = "Copying video…"
                        val draft = withContext(Dispatchers.IO) {
                            val m = graph.library.import(uris.first(), MediaType.Video)
                            val info = MediaProbe.video(m.file)
                            if (info == null) {
                                m.file.delete()
                                null
                            } else {
                                VideoDraft(m, info)
                            }
                        }
                        if (draft == null) toast("That video can't be read on this device.") else state.push(Screen.VideoEditor(draft))
                    }
                    MediaType.Audio -> {
                        val draft = withContext(Dispatchers.IO) {
                            val m = graph.library.import(uris.first(), MediaType.Audio)
                            val info = MediaProbe.audio(m.file)
                            if (info == null) {
                                m.file.delete()
                                null
                            } else {
                                AudioDraft(m, info)
                            }
                        }
                        if (draft == null) toast("That doesn't look like an audio file Sharpr can read.") else state.push(Screen.AudioEditor(draft))
                    }
                }
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't open that file.")
            } finally {
                state.importing = null
            }
        }
    }

    companion object {
        const val MAX_BATCH = 30
    }
}

@Composable
fun AppRoot(graph: Graph, settings: AppSettings, state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val c = Sharpr.colors

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    lateinit var actions: Actions
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Actions.MAX_BATCH)) { uris ->
        actions.import(MediaType.Image, uris)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) actions.import(MediaType.Video, listOf(uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) actions.import(MediaType.Audio, listOf(uri))
    }
    actions = remember(graph, state) {
        Actions(
            graph, state, snackbar, scope, context,
            pickImages = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            pickAudio = { audioPicker.launch("audio/*") },
            askNotifications = { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    }

    if (!settings.onboarded) {
        OnboardingScreen(actions, settings) { graph.settings.update { it.copy(onboarded = true) } }
        return
    }

    // Shared from another app.
    val share = state.pendingShare
    LaunchedEffect(share) {
        if (share != null) {
            state.pendingShare = null
            state.stack.clear()
            actions.import(share.first, share.second)
        }
    }

    // Finished jobs pop a snackbar with a shortcut to the result.
    LaunchedEffect(Unit) {
        graph.queue.events.collect { e ->
            when (e) {
                is JobEvent.Finished -> scope.launch {
                    state.libraryEpoch++
                    val r = snackbar.showSnackbar("${e.record.name} is ready", actionLabel = "View", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) state.openRecord(e.record.id)
                }
                is JobEvent.Failed -> scope.launch {
                    snackbar.showSnackbar("Couldn't finish ${e.job.spec.name}: ${e.job.error}", withDismissAction = true, duration = SnackbarDuration.Long)
                }
            }
        }
    }

    val jobs by graph.queue.jobs.collectAsStateWithLifecycle()
    val activeJobs = jobs.count { it.active }

    BackHandler(enabled = state.stack.isNotEmpty() || state.tab != Tab.Studio) {
        if (!state.pop()) state.tab = Tab.Studio
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(
            targetState = state.tab,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(220))) togetherWith fadeOut(tween(120)) },
            label = "tabs",
        ) { tab ->
            when (tab) {
                Tab.Studio -> StudioScreen(actions, settings)
                Tab.Library -> LibraryScreen(actions)
                Tab.Queue -> QueueScreen(actions)
                Tab.Settings -> SettingsScreen(actions, settings)
            }
        }

        AnimatedVisibility(
            visible = state.stack.isEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = slideInVertically { it * 2 } + fadeIn(),
            exit = slideOutVertically { it * 2 } + fadeOut(),
        ) {
            NavPill(
                items = listOf(
                    NavItem(R.drawable.ic_sparkle, "Studio"),
                    NavItem(R.drawable.ic_library, "Library"),
                    NavItem(R.drawable.ic_queue, "Queue", badge = activeJobs),
                    NavItem(R.drawable.ic_settings, "Settings"),
                ),
                selected = state.tab.ordinal,
                onSelect = { state.goTab(Tab.entries[it]) },
            )
        }

        // Pushed screens slide over the tabs.
        AnimatedContent(
            targetState = state.top,
            transitionSpec = {
                // Pushing keeps the old top in the stack; popping removed it.
                val forward = targetState != null && (initialState == null || initialState in state.stack)
                if (forward) {
                    (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(240))) togetherWith fadeOut(tween(160))
                } else {
                    fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)))
                }
            },
            label = "stack",
        ) { screen ->
            if (screen != null) {
                Box(Modifier.fillMaxSize().background(c.bg)) {
                    when (screen) {
                        is Screen.ImageEditor -> ImageEditorScreen(actions, settings, screen)
                        is Screen.VideoEditor -> VideoEditorScreen(actions, settings, screen)
                        is Screen.AudioEditor -> AudioEditorScreen(actions, settings, screen)
                        is Screen.Detail -> DetailScreen(actions, screen.id)
                    }
                }
            }
        }

        state.importing?.let { msg -> BusyOverlay(msg) }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (state.stack.isEmpty()) 86.dp else 12.dp),
        )
    }
}

@Composable
private fun BusyOverlay(message: String) {
    val c = Sharpr.colors
    Box(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.shadow(20.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(c.surface)
                .border(1.dp, c.outline, RoundedCornerShape(22.dp)).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(message, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("Copied into Sharpr so it can be re-processed later", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}
