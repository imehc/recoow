package imehc.recoow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.update.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val installed: InstalledAppVersion,
    val checked: Boolean = false,
    val busy: Boolean = false,
    val release: AppRelease? = null,
    val progress: Float? = null,
    val prepared: PreparedAppUpdate? = null,
    val problem: AppUpdateProblem? = null,
    val needsInstallPermission: Boolean = false,
)

@HiltViewModel
class AppUpdateViewModel @Inject constructor(private val updates: AppUpdates) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUpdateUiState(updates.installed))
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun checkOnce() { if (!state.value.checked && !state.value.busy) check() }
    fun check() = runOperation {
        val release = updates.check()
        mutableState.update { it.copy(checked = true, release = release, prepared = null, needsInstallPermission = false) }
    }
    fun download() {
        val release = state.value.release ?: return
        runOperation {
            mutableState.update { it.copy(progress = 0f, prepared = null) }
            val prepared = updates.download(release) { completed, total ->
                mutableState.update { it.copy(progress = (completed.toDouble() / total).toFloat()) }
            }
            mutableState.update { it.copy(prepared = prepared) }
        }
    }
    fun cancelDownload() { if (state.value.progress != null) job?.cancel() }
    fun requireInstallPermission() { mutableState.update { it.copy(needsInstallPermission = true) } }
    fun install(onUri: (String) -> Unit) {
        val prepared = state.value.prepared ?: return
        runOperation { onUri(updates.installationUri(prepared)) }
    }
    fun installerFailed() { mutableState.update { it.copy(problem = AppUpdateProblem.INSTALLER) } }

    private fun runOperation(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, problem = null) }
        job = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: AppUpdateException) { mutableState.update { it.copy(problem = error.problem) } }
            catch (_: Exception) { mutableState.update { it.copy(problem = AppUpdateProblem.CONNECTION) } }
            finally { mutableState.update { it.copy(busy = false, progress = null) } }
        }
    }
}
