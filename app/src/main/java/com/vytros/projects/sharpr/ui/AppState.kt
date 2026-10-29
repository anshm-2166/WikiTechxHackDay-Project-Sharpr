package com.vytros.projects.sharpr.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vytros.projects.sharpr.data.ImportedMedia
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.ImageAnalysis
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.VideoOptions
import com.vytros.projects.sharpr.engine.media.AudioInfo
import com.vytros.projects.sharpr.engine.media.VideoInfo

data class ImageDraft(val media: ImportedMedia, val analysis: ImageAnalysis)
data class VideoDraft(val media: ImportedMedia, val info: VideoInfo)
data class AudioDraft(val media: ImportedMedia, val info: AudioInfo)

/** Screens stacked above the tabs. */
sealed interface Screen {
    data class ImageEditor(val drafts: List<ImageDraft>, val initial: UpscaleOptions? = null, val replaceId: String? = null) : Screen
    data class VideoEditor(val draft: VideoDraft, val initial: VideoOptions? = null) : Screen
    data class AudioEditor(val draft: AudioDraft, val initial: AudioOptions? = null) : Screen
    data class Detail(val id: String) : Screen
}

enum class Tab { Studio, Library, Queue, Settings }

/** UI navigation state. Survives theme changes; the activity handles configuration changes itself. */
class AppState {
    var tab by mutableStateOf(Tab.Studio)
    val stack = mutableStateListOf<Screen>()

    /** Non-null while a picked file is being copied in and analysed. */
    var importing by mutableStateOf<String?>(null)

    /** Media handed to us by another app, waiting for the UI to import it. */
    var pendingShare by mutableStateOf<Pair<MediaType, List<Uri>>?>(null)

    /** Bumped to scroll the library to the top after something new lands. */
    var libraryEpoch by mutableIntStateOf(0)

    val top: Screen? get() = stack.lastOrNull()

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(s: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(s)
    }

    fun goTab(t: Tab) {
        stack.clear()
        tab = t
    }

    fun openRecord(id: String) {
        stack.clear()
        tab = Tab.Library
        stack.add(Screen.Detail(id))
    }

    fun openQueue() = goTab(Tab.Queue)

    fun shared(type: MediaType, uris: List<Uri>) {
        pendingShare = type to uris
    }
}
