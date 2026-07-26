package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ResolveState {
  data object Idle : ResolveState
  data object Resolving : ResolveState
  data class Resolved(val result: ResolvedSource) : ResolveState
  data class Error(
    val message: String,
    val cause: Throwable? = null,
  ) : ResolveState
}

/**
 * UI state for the single embedded [api] instance. Ketch runs
 * strictly as a local download manager: there is exactly one
 * active backend for the lifetime of this state holder.
 */
class AppState(
  private val api: KetchApi,
  private val scope: CoroutineScope,
  private val onOpenBrowser: ((String?) -> Unit)? = null,
) {
  val tasks: StateFlow<List<DownloadTask>> = api.tasks

  val sortedTasks: StateFlow<List<DownloadTask>> =
    tasks.map { it.sortedByDescending { t -> t.createdAt } }
      .stateIn(
        scope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
      )

  // UI state
  var statusFilter by mutableStateOf(StatusFilter.All)
  var errorMessage by mutableStateOf<String?>(null)
  var showAddDialog by mutableStateOf(false)
  var resolveState by mutableStateOf<ResolveState>(
    ResolveState.Idle
  )
    private set
  private var resolvingUrl: String? = null

  /** Handle "New Task" action. */
  fun requestAddDownload() {
    showAddDialog = true
  }

  /**
   * Handle the "Browser" action. The in-app browser is currently
   * Android-only (see `BrowserActivity`); on platforms that don't
   * supply [onOpenBrowser], surface the same error banner used
   * elsewhere rather than showing a dead button.
   */
  fun requestOpenBrowser(url: String? = null) {
    if (onOpenBrowser == null) {
      errorMessage = "In-app browser is not available on this platform"
      return
    }
    onOpenBrowser.invoke(url)
  }

  fun resolveUrl(url: String) {
    resolvingUrl = url
    resolveState = ResolveState.Resolving
    scope.launch {
      runCatching {
        api.resolve(url)
      }.onSuccess { result ->
        if (resolvingUrl == url) {
          resolveState = ResolveState.Resolved(result)
        }
      }.onFailure { e ->
        if (resolvingUrl == url) {
          resolveState = ResolveState.Error(
            message = e.message ?: "Failed to resolve URL",
            cause = e,
          )
        }
      }
    }
  }

  fun resetResolveState() {
    resolvingUrl = null
    resolveState = ResolveState.Idle
  }

  fun startDownload(
    url: String,
    fileName: String,
    speedLimit: SpeedLimit,
    priority: DownloadPriority,
    schedule: DownloadSchedule = DownloadSchedule.Immediate,
    resolvedUrl: ResolvedSource? = null,
    selectedFileIds: Set<String> = emptySet(),
  ) {
    scope.launch {
      runCatching {
        val request = DownloadRequest(
          url = url,
          destination = fileName.ifBlank { null }
            ?.let { Destination(it) },
          speedLimit = speedLimit,
          priority = priority,
          schedule = schedule,
          resolvedSource = resolvedUrl,
          selectedFileIds = selectedFileIds,
        )
        api.download(request)
      }.onFailure { e ->
        errorMessage =
          e.message ?: "Failed to start download"
      }
    }
  }

  fun pauseAll() {
    scope.launch {
      tasks.value.forEach { task ->
        if (task.state.value.isActive) task.pause()
      }
    }
  }

  fun resumeAll() {
    scope.launch {
      tasks.value.forEach { task ->
        if (task.state.value is DownloadState.Paused) {
          task.resume()
        }
      }
    }
  }

  fun clearCompleted() {
    scope.launch {
      tasks.value.forEach { task ->
        if (task.state.value is DownloadState.Completed) {
          task.remove()
        }
      }
    }
  }

  fun dismissError() {
    errorMessage = null
  }
}
