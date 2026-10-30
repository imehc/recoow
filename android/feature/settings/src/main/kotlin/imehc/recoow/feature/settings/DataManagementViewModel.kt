package imehc.recoow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.backup.*
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DataResult { EXPORTED, IMPORTED, OPTIMIZED, RESET, FAILED, INVALID_BACKUP, LEGACY_BACKUP }
data class DataManagementState(val usage: StorageUsage? = null, val preview: PreparedBackup? = null,
    val busy: Boolean = false, val result: DataResult? = null, val count: Long = 0)

@HiltViewModel
class DataManagementViewModel @Inject constructor(private val data: DataManagement) : ViewModel() {
    private val mutable = MutableStateFlow(DataManagementState())
    val state = mutable.asStateFlow()
    init { refresh() }
    fun refresh() = run { mutable.update { it.copy(usage = data.usage()) } }
    fun export(destination: () -> OutputStream) = run {
        data.export(destination); mutable.update { it.copy(result = DataResult.EXPORTED, usage = data.usage()) }
    }
    fun preview(source: () -> InputStream) = run {
        mutable.value.preview?.let(data::discard)
        mutable.update { it.copy(preview = null) }
        val preview = data.preview(source)
        mutable.update { it.copy(preview = preview) }
    }
    fun dismissPreview() { if (!mutable.value.busy) { mutable.value.preview?.let(data::discard); mutable.update { it.copy(preview = null) } } }
    fun import(mode: ImportMode, scopes: Set<ImportScope>) = run {
        val backup = requireNotNull(mutable.value.preview)
        val count = data.import(backup, mode, scopes)
        data.discard(backup)
        mutable.update { it.copy(preview = null, result = DataResult.IMPORTED, count = count.toLong(), usage = data.usage()) }
    }
    fun optimize() = run {
        val reclaimed = data.optimize()
        mutable.update { it.copy(result = DataResult.OPTIMIZED, count = reclaimed, usage = data.usage()) }
    }
    fun reset() = run { data.reset(); mutable.update { it.copy(preview = null, result = DataResult.RESET, usage = data.usage()) } }
    private fun run(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: LegacyBackupException) { mutable.update { it.copy(result = DataResult.LEGACY_BACKUP) } }
            catch (_: InvalidBackupException) { mutable.update { it.copy(result = DataResult.INVALID_BACKUP) } }
            catch (_: Exception) { mutable.update { it.copy(result = DataResult.FAILED) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { mutable.value.preview?.let(data::discard) }
}
