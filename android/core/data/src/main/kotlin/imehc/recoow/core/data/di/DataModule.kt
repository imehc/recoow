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
import imehc.recoow.core.domain.records.RecordRepository
import imehc.recoow.core.data.preferences.PreferenceBackupAdapter
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.backup.LocalRollbackFiles
import java.io.File
import java.util.UUID
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

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, preferences: DataStore<Preferences>): DatabaseHost {
        val root = File(context.noBackupFilesDir, "records")
        val identity = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
        val id = identity.getString("id", null) ?: UUID.randomUUID().toString().also {
            check(identity.edit().putString("id", it).commit())
        }
        return DatabaseHost(context, root, LocalRollbackFiles(root, File(context.noBackupFilesDir, "rollback"),
            PreferenceBackupAdapter(context, preferences), id))
    }

    @Provides @Singleton
    fun records(database: DatabaseHost): RecordRepository = RoomRecordRepository(database)

    @Provides @Singleton
    fun receivedRecords(database: DatabaseHost): ReceivedRecordStore = ReceivedRecordStore(database)
}
