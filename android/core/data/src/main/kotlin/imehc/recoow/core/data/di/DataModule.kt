package imehc.recoow.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.data.preferences.AndroidPreferencesRepository
import imehc.recoow.core.domain.preferences.PreferencesRepository
import javax.inject.Singleton

private val Context.preferenceStore by preferencesDataStore(name = "app_preferences")

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds @Singleton
    abstract fun preferences(repository: AndroidPreferencesRepository): PreferencesRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun preferenceStore(@ApplicationContext context: Context): DataStore<Preferences> = context.preferenceStore
}
