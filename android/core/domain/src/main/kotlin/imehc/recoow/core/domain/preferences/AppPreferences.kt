package imehc.recoow.core.domain.preferences

import kotlinx.coroutines.flow.Flow

enum class Appearance { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val tag: String) {
    SYSTEM(""), CHINESE("zh-Hans"), ENGLISH("en"),
}

data class AppPreferences(
    val appearance: Appearance = Appearance.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val addsPickedPhotosToMediaLibrary: Boolean = false,
    val savesCameraPhotosToLibrary: Boolean = true,
)

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>
    suspend fun setAppearance(value: Appearance)
    suspend fun setLanguage(value: AppLanguage)
    suspend fun setAddsPickedPhotosToMediaLibrary(value: Boolean)
    suspend fun setSavesCameraPhotosToLibrary(value: Boolean)
}
