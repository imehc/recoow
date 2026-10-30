package imehc.recoow.core.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.Appearance
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePreferencesRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun repository(file: File, job: Job): DataStorePreferencesRepository =
        DataStorePreferencesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })

    @Test fun freshInstallFollowsSystem() = runTest {
        val job = SupervisorJob()
        try {
            assertEquals(AppPreferences(), repository(File(folder.root, "defaults.preferences_pb"), job).preferences.first())
        } finally { job.cancelAndJoin() }
    }

    @Test fun independentUpdatesSurviveAReopenedStore() = runTest {
        val file = File(folder.root, "settings.preferences_pb")
        val firstJob = SupervisorJob()
        val first = repository(file, firstJob)
        try {
            val appearance = launch { first.setAppearance(Appearance.DARK) }
            val language = launch { first.setLanguage(AppLanguage.CHINESE) }
            appearance.join()
            language.join()
            assertEquals(AppPreferences(Appearance.DARK, AppLanguage.CHINESE), first.preferences.first())
        } finally { firstJob.cancelAndJoin() }
        val secondJob = SupervisorJob()
        try {
            val second = repository(file, secondJob)
            assertEquals(AppPreferences(Appearance.DARK, AppLanguage.CHINESE), second.preferences.first())
            second.setLanguage(AppLanguage.SYSTEM)
            assertEquals(AppPreferences(Appearance.DARK, AppLanguage.SYSTEM), second.preferences.first())
        } finally { secondJob.cancelAndJoin() }
    }

    @Test fun photoDefaultsAndIndependentTogglesSurviveReopen() = runTest {
        val file = File(folder.root, "photos.preferences_pb")
        val job = SupervisorJob()
        try {
            val repository = repository(file, job)
            assertEquals(false, repository.preferences.first().addsPickedPhotosToMediaLibrary)
            assertEquals(true, repository.preferences.first().savesCameraPhotosToLibrary)
            repository.setAddsPickedPhotosToMediaLibrary(true)
            repository.setSavesCameraPhotosToLibrary(false)
        } finally { job.cancelAndJoin() }
        val reopened = SupervisorJob()
        try {
            val prefs = repository(file, reopened).preferences.first()
            assertEquals(true, prefs.addsPickedPhotosToMediaLibrary)
            assertEquals(false, prefs.savesCameraPhotosToLibrary)
            assertEquals(Appearance.SYSTEM, prefs.appearance)
        } finally { reopened.cancelAndJoin() }
    }

    @Test fun unknownFutureValuesFallBackWithoutErasingOtherPreferences() = runTest {
        val file = File(folder.root, "future.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            store.edit {
                it[stringPreferencesKey("appearance")] = "FUTURE_THEME"
                it[stringPreferencesKey("language")] = "FUTURE_LANGUAGE"
                it[stringPreferencesKey("future_field")] = "preserve"
            }
            val repository = DataStorePreferencesRepository(store)
            assertEquals(AppPreferences(), repository.preferences.first())
            repository.setAppearance(Appearance.DARK)
            assertEquals("preserve", store.data.first()[stringPreferencesKey("future_field")])
            assertEquals(AppPreferences(appearance = Appearance.DARK), repository.preferences.first())
        } finally { job.cancelAndJoin() }
    }

    @Test fun corruptPreferencesReportFailureAndPreserveOriginalBytes() = runTest {
        val file = File(folder.root, "corrupt.preferences_pb")
        val original = byteArrayOf(0, -1, 0, -1)
        file.writeBytes(original)
        val job = SupervisorJob()
        try {
            try {
                repository(file, job).preferences.first()
                fail("Corrupt preferences must not silently reset")
            } catch (_: CorruptionException) {
                assertArrayEquals(original, file.readBytes())
            }
        } finally { job.cancelAndJoin() }
    }
}
