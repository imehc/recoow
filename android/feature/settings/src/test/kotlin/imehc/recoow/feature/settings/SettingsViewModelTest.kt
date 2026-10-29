package imehc.recoow.feature.settings

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.domain.preferences.PreferencesRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = TestPreferencesRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(repository).also { store.put("settings", it) }

    @Test fun emitsLoadingThenStoredPreferences() = runTest(dispatcher) {
        repository.values.value = AppPreferences(Appearance.DARK, AppLanguage.CHINESE)
        val model = viewModel()
        model.uiState.test {
            assertNull(awaitItem().preferences)
            assertEquals(repository.values.value, awaitItem().preferences)
        }
    }

    @Test fun readFailureRemainsVisibleUntilSuccessfulRetry() = runTest(dispatcher) {
        repository.failRead = true
        val model = viewModel()
        advanceUntilIdle()
        assertTrue(model.uiState.value.readFailed)
        assertNull(model.uiState.value.preferences)
        repository.failRead = false
        model.retry()
        advanceUntilIdle()
        assertFalse(model.uiState.value.readFailed)
        assertEquals(AppPreferences(), model.uiState.value.preferences)
        assertEquals(2, repository.readCount)
    }

    @Test fun saveFailureKeepsPreviousValueAndNextSaveRecovers() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        repository.failSave = true
        model.setAppearance(Appearance.DARK)
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.saving)
        assertEquals(Appearance.SYSTEM, model.uiState.value.preferences?.appearance)
        repository.failSave = false
        model.setAppearance(Appearance.LIGHT)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saveFailed)
        assertEquals(Appearance.LIGHT, model.uiState.value.preferences?.appearance)
    }

    @Test fun pendingSaveDisablesAnotherSubmissionWithoutOptimisticSuccess() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        repository.saveGate = CompletableDeferred()
        model.setAppearance(Appearance.DARK)
        advanceUntilIdle()
        assertTrue(model.uiState.value.saving)
        assertEquals(Appearance.SYSTEM, model.uiState.value.preferences?.appearance)
        model.setAppearance(Appearance.LIGHT)
        repository.saveGate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saving)
        assertEquals(1, repository.saveCount)
        assertEquals(Appearance.DARK, model.uiState.value.preferences?.appearance)
    }

    @Test fun languageUpdatePreservesAppearance() = runTest(dispatcher) {
        repository.values.value = AppPreferences(appearance = Appearance.DARK)
        val model = viewModel()
        advanceUntilIdle()
        model.setLanguage(AppLanguage.CHINESE)
        advanceUntilIdle()
        assertEquals(AppPreferences(Appearance.DARK, AppLanguage.CHINESE), model.uiState.value.preferences)
    }

    @Test fun clearingViewModelCancelsPendingWriteWithoutShowingSaveError() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        repository.saveGate = CompletableDeferred()
        model.setAppearance(Appearance.DARK)
        advanceUntilIdle()
        store.clear()
        advanceUntilIdle()
        repository.saveGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(Appearance.SYSTEM, repository.values.value.appearance)
        assertFalse(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.saving)
    }

    private class TestPreferencesRepository : PreferencesRepository {
        val values = MutableStateFlow(AppPreferences())
        var failRead = false
        var failSave = false
        var readCount = 0
        var saveCount = 0
        var saveGate: CompletableDeferred<Unit>? = null
        override val preferences = flow {
            readCount++
            if (failRead) throw IOException("Read unavailable")
            emitAll(values)
        }
        private suspend fun beforeSave() {
            saveCount++
            saveGate?.await()
            if (failSave) throw IOException("Storage full")
        }
        override suspend fun setAppearance(value: Appearance) {
            beforeSave()
            values.update { it.copy(appearance = value) }
        }
        override suspend fun setLanguage(value: AppLanguage) {
            beforeSave()
            values.update { it.copy(language = value) }
        }
    }
}
