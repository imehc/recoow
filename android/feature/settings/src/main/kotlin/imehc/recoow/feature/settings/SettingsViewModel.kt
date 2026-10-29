package imehc.recoow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.domain.preferences.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: AppPreferences? = null,
    val saving: Boolean = false,
    val readFailed: Boolean = false,
    val saveFailed: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: PreferencesRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val uiState = mutableState.asStateFlow()
    private var observation: Job? = null

    init { retry() }

    fun retry() {
        observation?.cancel()
        mutableState.update { it.copy(readFailed = false) }
        observation = viewModelScope.launch {
            try {
                repository.preferences.collect { preferences -> mutableState.update { it.copy(preferences = preferences) } }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.update { it.copy(readFailed = true) } }
        }
    }

    fun setAppearance(value: Appearance) = save { repository.setAppearance(value) }
    fun setLanguage(value: AppLanguage) = save { repository.setLanguage(value) }

    private fun save(action: suspend () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try { action()
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.update { it.copy(saveFailed = true) }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}
