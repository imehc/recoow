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
    fun statistics(database: DatabaseHost): imehc.recoow.core.domain.statistics.StatisticsRepository =
        imehc.recoow.core.data.statistics.RoomStatisticsRepository(database)
    @Provides @Singleton
    fun history(database: DatabaseHost, bills: imehc.recoow.core.domain.bills.BillsRepository,
        reminders: imehc.recoow.core.domain.reminders.RemindersRepository, food: imehc.recoow.core.domain.food.FoodRepository,
        diary: imehc.recoow.core.domain.diary.DiaryRepository, items: imehc.recoow.core.domain.items.ItemsRepository,
        decisions: imehc.recoow.core.domain.decision.DecisionsRepository, anniversaries: imehc.recoow.core.domain.anniversaries.AnniversariesRepository,
        tracks: imehc.recoow.core.domain.tracks.TracksRepository): imehc.recoow.core.domain.history.HistoryRepository =
        imehc.recoow.core.data.history.RoomHistoryRepository(database, bills, reminders, food, diary, items, decisions, anniversaries, tracks)
    @Provides @Singleton
    fun tracks(database: DatabaseHost, identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.tracks.TracksRepository =
        imehc.recoow.core.data.tracks.RoomTracksRepository(database, identity.id)
    @Provides @Singleton
    fun anniversaries(database: DatabaseHost, identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.anniversaries.AnniversariesRepository =
        imehc.recoow.core.data.anniversaries.RoomAnniversariesRepository(database, identity.id)
    @Provides @Singleton
    fun decisions(database: DatabaseHost, identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.decision.DecisionsRepository =
        imehc.recoow.core.data.decision.RoomDecisionsRepository(database, identity.id)
    @Provides @Singleton
    fun diary(@ApplicationContext context: Context, database: DatabaseHost, media: imehc.recoow.core.data.media.RoomMediaRepository,
        identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.diary.DiaryRepository =
        imehc.recoow.core.data.journal.RoomDiaryRepository(database, media, identity.id, context)
    @Provides @Singleton
    fun food(database: DatabaseHost, media: imehc.recoow.core.data.media.RoomMediaRepository,
        identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.food.FoodRepository =
        imehc.recoow.core.data.journal.RoomFoodRepository(database, media, identity.id)
    @Provides @Singleton
    fun items(database: DatabaseHost, identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.items.ItemsRepository =
        imehc.recoow.core.data.journal.RoomItemsRepository(database, identity.id)
    @Provides @Singleton
    fun reminders(database: DatabaseHost, identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.reminders.RemindersRepository =
        imehc.recoow.core.data.reminders.RoomRemindersRepository(database, identity.id)
    @Provides @Singleton
    fun bills(database: DatabaseHost, identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.domain.bills.BillsRepository =
        imehc.recoow.core.data.bills.RoomBillsRepository(database, identity.id)
    @Provides @Singleton
    fun roomMedia(@ApplicationContext context: Context, database: DatabaseHost,
        identity: imehc.recoow.core.domain.sync.DeviceIdentity): imehc.recoow.core.data.media.RoomMediaRepository =
        imehc.recoow.core.data.media.RoomMediaRepository(database, File(context.cacheDir, "resolved-media"), identity.id)
    @Provides @Singleton
    fun media(repository: imehc.recoow.core.data.media.RoomMediaRepository): imehc.recoow.core.domain.media.MediaRepository = repository
    @Provides @Singleton
    fun preferenceStore(@ApplicationContext context: Context): DataStore<Preferences> = context.preferenceStore

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, preferences: DataStore<Preferences>, deviceIdentity: imehc.recoow.core.domain.sync.DeviceIdentity): DatabaseHost {
        val root = File(context.noBackupFilesDir, "records")
        val identity = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
        val id = identity.getString("id", null) ?: UUID.randomUUID().toString().also {
            check(identity.edit().putString("id", it).commit())
        }
        return DatabaseHost(context, root, LocalRollbackFiles(root, File(context.noBackupFilesDir, "rollback"),
            PreferenceBackupAdapter(context, preferences), id), clientSource = imehc.recoow.core.database.LocalClientSource.current(context, deviceIdentity.id))
    }

    @Provides @Singleton
    fun records(database: DatabaseHost): RecordRepository = RoomRecordRepository(database)

    @Provides @Singleton
    fun receivedRecords(database: DatabaseHost): ReceivedRecordStore = ReceivedRecordStore(database)
}
