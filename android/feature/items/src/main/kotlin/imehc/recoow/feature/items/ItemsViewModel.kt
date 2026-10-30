package imehc.recoow.feature.items

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.items.*
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.preferences.*
import imehc.recoow.core.domain.records.*
import imehc.recoow.core.media.ImageProcessor
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ItemsState(val data: ItemsSnapshot = ItemsSnapshot(), val preferences: AppPreferences = AppPreferences(),
    val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val error: Int? = null)
@HiltViewModel
class ItemsViewModel @Inject constructor(private val repository: ItemsRepository, val media: MediaRepository,
    val processor: ImageProcessor, private val preferences: PreferencesRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(ItemsState()); val state = mutable.asStateFlow()
    val draft = saved.getStateFlow<ItemDraft?>("itemDraft", null)
    val detail = saved.getStateFlow<String?>("itemDetail", null)
    val managing = saved.getStateFlow("itemCategories", false)
    val categoryDraft = saved.getStateFlow<NamedRecordDraft?>("itemCategoryDraft", null)
    val query = saved.getStateFlow("itemQuery", "")
    val categoryFilter = saved.getStateFlow<String?>("itemCategoryFilter", null)
    private var observation: Job? = null
    init { retry() }
    fun retry() {
        observation?.cancel(); mutable.update { it.copy(loading = true, failed = false) }
        observation = viewModelScope.launch { try {
            combine(repository.snapshot, preferences.preferences) { data, prefs -> data to prefs }.collect { (data, prefs) ->
                mutable.update { it.copy(data = data, preferences = prefs, loading = false) }
                if (categoryFilter.value != null && data.categories.none { it.id == categoryFilter.value }) saved["itemCategoryFilter"] = null
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true) } } }
    }
    fun query(value: String) { saved["itemQuery"] = value }
    fun filterCategory(value: String?) { saved["itemCategoryFilter"] = value }
    fun show(id: String) { saved["itemDetail"] = id; saved["itemDraft"] = null; saved["itemCategories"] = false }
    fun edit(entry: StoredItem? = null) { saved["itemDraft"] = entry?.let(ItemDraft::from) ?: ItemDraft() }
    fun update(value: ItemDraft) { if (!state.value.busy) saved["itemDraft"] = value }
    fun manageCategories() { saved["itemCategories"] = true }
    fun editCategory(value: NamedRecordDraft) { if (!state.value.busy) saved["itemCategoryDraft"] = value }
    fun back() { if (!state.value.busy) when {
        categoryDraft.value != null -> saved["itemCategoryDraft"] = null; managing.value -> saved["itemCategories"] = false
        draft.value != null -> saved["itemDraft"] = null; else -> saved["itemDetail"] = null
    } }
    fun save() = action {
        val value = requireNotNull(draft.value); val id = repository.save(value)
        saved["itemDraft"] = null; saved["itemDetail"] = id; value.imageContent?.file?.delete()
    }
    fun saveCategory() = action { repository.saveCategory(requireNotNull(categoryDraft.value)); saved["itemCategoryDraft"] = null }
    fun deleteCategory(category: NamedRecord) = action { repository.deleteCategory(category.id, category.revision) }
    fun delete(entry: StoredItem) = action { repository.delete(entry.id, entry.revision); saved["itemDetail"] = null }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e }
            catch (_: StaleRecordException) { mutable.update { it.copy(error = CommonR.string.record_stale) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(error = CommonR.string.record_invalid) } }
            catch (_: Exception) { mutable.update { it.copy(error = CommonR.string.record_failed) } }
            finally { mutable.update { it.copy(busy = false) } } }
    }
}
