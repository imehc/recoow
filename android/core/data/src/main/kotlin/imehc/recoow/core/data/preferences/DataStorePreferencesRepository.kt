package imehc.recoow.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.domain.preferences.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStorePreferencesRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : PreferencesRepository {
    override val preferences = store.data.map { values ->
        AppPreferences(
            appearance = Appearance.entries.firstOrNull { it.name == values[APPEARANCE] } ?: Appearance.SYSTEM,
            language = AppLanguage.entries.firstOrNull { it.name == values[LANGUAGE] } ?: AppLanguage.SYSTEM,
            addsPickedPhotosToMediaLibrary = values[ADD_PHOTOS] ?: false,
            savesCameraPhotosToLibrary = values[SAVE_CAMERA] ?: true,
        )
    }.distinctUntilChanged()

    override suspend fun setAppearance(value: Appearance) { store.edit { it[APPEARANCE] = value.name } }
    override suspend fun setLanguage(value: AppLanguage) { store.edit { it[LANGUAGE] = value.name } }
    override suspend fun setAddsPickedPhotosToMediaLibrary(value: Boolean) { store.edit { it[ADD_PHOTOS] = value } }
    override suspend fun setSavesCameraPhotosToLibrary(value: Boolean) { store.edit { it[SAVE_CAMERA] = value } }

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        val LANGUAGE = stringPreferencesKey("language")
        val ADD_PHOTOS = booleanPreferencesKey("addsPickedPhotosToMediaLibrary")
        val SAVE_CAMERA = booleanPreferencesKey("savesCameraPhotosToLibrary")
    }
}
