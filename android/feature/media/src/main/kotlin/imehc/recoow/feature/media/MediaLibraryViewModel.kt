package imehc.recoow.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.PreferencesRepository
import imehc.recoow.core.media.ImageProcessor
import imehc.recoow.core.media.R
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MediaLibraryState(val assets: List<MediaAsset> = emptyList(), val preferences: AppPreferences? = null,
    val loading: Boolean = true, val busy: Boolean = false, val error: Int? = null, val readFailed: Boolean = false)

@HiltViewModel
class MediaLibraryViewModel @Inject constructor(val repository: MediaRepository, val processor: ImageProcessor,
    private val preferences: PreferencesRepository) : ViewModel() {
    private val mutable = MutableStateFlow(MediaLibraryState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    init {
        retry()
        viewModelScope.launch {
            try { repository.collectOrphans(); processor.cleanExpiredDrafts() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Cleanup can retry when reopened. */ }
        }
    }
    fun retry() {
        observation?.cancel()
        mutable.update { it.copy(loading = true, readFailed = false, error = null) }
        observation = viewModelScope.launch {
            try {
                combine(repository.assets, preferences.preferences) { assets, prefs -> assets to prefs }.collect { (assets, prefs) ->
                    mutable.update { it.copy(assets = assets, preferences = prefs, loading = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, readFailed = true, error = R.string.failed) } }
        }
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun rename(id: String, title: String) = action { repository.rename(id, title) }
    fun delete(id: String) = action { repository.delete(id) }
    fun deleteSelected(ids: Set<String>) = action {
        val assets = repository.assets.first()
        assets.firstOrNull { it.id in ids && it.references > 0 }?.let { throw AssetInUseException(it.references) }
        ids.forEach { repository.delete(it) }
    }
    fun reorder(ids: List<String>) = action { repository.reorder(ids) }
    fun saveImage(replacing: String?, content: PreparedMedia) = action {
        if (replacing == null) repository.createImage(content) else repository.replaceImage(replacing, content)
        content.file.delete()
    }
    fun move(id: String, delta: Int) = action {
        val ids = mutable.value.assets.map { it.id }.toMutableList()
        val index = ids.indexOf(id); val target = index + delta
        if (index >= 0 && target in ids.indices) { ids.removeAt(index); ids.add(target, id); repository.reorder(ids) }
    }
    fun addToAssets(value: Boolean) = action { preferences.setAddsPickedPhotosToMediaLibrary(value) }
    fun saveCamera(value: Boolean) = action { preferences.setSavesCameraPhotosToLibrary(value) }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: AssetInUseException) { mutable.update { it.copy(error = R.string.in_use) } }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.failed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
