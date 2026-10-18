package com.vytros.projects.sharpr.engine

import com.vytros.projects.sharpr.data.SettingsStore
import com.vytros.projects.sharpr.data.UpscaleRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface JobState {
    data object Idle : JobState

    data class Running(
        val stage: String = "Preparing",
        val fraction: Float = 0f,
        val tilesDone: Int = 0,
        val tilesTotal: Int = 0,
        val backend: String? = null,
        val fellBack: Boolean = false,
        val title: String = "",
    ) : JobState

    data class Done(val record: UpscaleRecord, val replacedId: String?) : JobState
    data class Failed(val message: String) : JobState
}

/** App-scoped so a running job survives rotation and screen changes. */
class UpscaleManager(
    private val pipeline: UpscalePipeline,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state

    fun start(req: UpscaleRequest) {
        if (_state.value is JobState.Running) return
        _state.value = JobState.Running(title = req.name)
        job = scope.launch {
            val progress = object : PipelineProgress {
                override fun stage(label: String, fraction: Float) = patch { it.copy(stage = label, fraction = fraction) }
                override fun tiles(done: Int, total: Int) = patch { it.copy(tilesDone = done, tilesTotal = total) }
                override fun backend(name: String, fallback: Boolean) =
                    patch { it.copy(backend = name, fellBack = it.fellBack || fallback) }
            }
            try {
                val record = pipeline.run(req, settings.state.value, progress)
                _state.value = JobState.Done(record, req.replaceId)
            } catch (e: CancellationException) {
                _state.value = JobState.Idle
            } catch (e: UpscaleException) {
                _state.value = JobState.Failed(e.message ?: "Something went wrong.")
            } catch (e: Throwable) {
                _state.value = JobState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        pipeline.cancel()
        job?.cancel()
    }

    /** Called by the UI once it has handled Done/Failed. */
    fun acknowledge() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }

    private inline fun patch(f: (JobState.Running) -> JobState.Running) {
        val cur = _state.value
        if (cur is JobState.Running) _state.value = f(cur)
    }
}
