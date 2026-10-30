package imehc.recoow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.sync.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@HiltViewModel
class SyncViewModel @Inject constructor(private val repository: SyncRepository) : ViewModel() {
    val state = repository.state
    init { action { repository.refresh() } }
    fun connect(endpoint: String, method: ConnectionMethod, secret: String, device: String, replace: Boolean) = action {
        repository.connect(endpoint, method, secret, device, replace)
        if (repository.state.value.connection != null && repository.state.value.problem == null) repository.sync()
    }
    fun setEnabled(value: Boolean) = action { repository.setEnabled(value) }
    fun refreshDevices() = action { repository.refreshDevices() }
    fun revokeDevice(id: String) = action { repository.revokeDevice(id) }
    fun synchronize() = action { repository.sync() }
    fun disconnect() = action { repository.disconnect() }
    fun acknowledgeRecoveryKey() = action { repository.acknowledgeRecoveryKey() }
    fun generatePairingCode() = action { repository.generatePairingCode() }
    fun dismissPairingCode() = action { repository.dismissPairingCode() }
    fun resolve(conflict: RecordConflict, local: Boolean) = action {
        repository.resolve(conflict.table, conflict.id, conflict.remoteVersion, local)
        repository.sync()
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Repository state carries recoverable errors. */ }
    } }
}
