package imehc.recoow.core.data.preferences

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.domain.preferences.PreferencesRepository
import imehc.recoow.core.database.DatabaseHost
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext

/** Android 13+ owns app locales, including changes made outside the app. */
class AndroidPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stored: DataStorePreferencesRepository,
    private val database: DatabaseHost,
) : PreferencesRepository {
    private val observedPreferences = if (Build.VERSION.SDK_INT >= 33) {
        combine(stored.preferences, systemLanguage()) { preferences, language ->
            preferences.copy(language = language)
        }.distinctUntilChanged()
    } else {
        stored.preferences
    }

    override val preferences = flow {
        database.access { } // Finish interrupted restores before presenting any persisted preference.
        emitAll(observedPreferences)
    }

    override suspend fun setToolVisible(id: String, visible: Boolean) = database.access { stored.setToolVisible(id, visible) }
    override suspend fun setAppearance(value: Appearance) = database.access { stored.setAppearance(value) }
    override suspend fun setAddsPickedPhotosToMediaLibrary(value: Boolean) = database.access { stored.setAddsPickedPhotosToMediaLibrary(value) }
    override suspend fun setSavesCameraPhotosToLibrary(value: Boolean) = database.access { stored.setSavesCameraPhotosToLibrary(value) }

    override suspend fun setLanguage(value: AppLanguage) = database.access {
        if (Build.VERSION.SDK_INT >= 33) {
            withContext(Dispatchers.Main.immediate) {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(value.tag)
            }
        } else {
            stored.setLanguage(value)
        }
    }

    @RequiresApi(33)
    private fun systemLanguage() = callbackFlow {
        val manager = context.getSystemService(LocaleManager::class.java)
        fun currentLanguage(): AppLanguage = when (manager.applicationLocales.get(0)?.language) {
            "zh" -> AppLanguage.CHINESE
            "en" -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
        val callback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) { trySend(currentLanguage()) }
            @Deprecated("No memory resources are retained")
            override fun onLowMemory() = Unit
        }
        context.registerComponentCallbacks(callback)
        trySend(currentLanguage())
        awaitClose { context.unregisterComponentCallbacks(callback) }
    }.distinctUntilChanged()
}
