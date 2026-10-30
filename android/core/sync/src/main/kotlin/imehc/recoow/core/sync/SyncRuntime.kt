package imehc.recoow.core.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.sync.SyncRepository
import imehc.recoow.core.domain.sync.SyncProblem
import imehc.recoow.core.network.SyncApiFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides @Singleton fun deviceIdentity(@ApplicationContext context: Context): imehc.recoow.core.domain.sync.DeviceIdentity =
        object : imehc.recoow.core.domain.sync.DeviceIdentity { override val id: String get() = CredentialVault(context).localDeviceId }

    @Provides @Singleton fun repository(@ApplicationContext context: Context, host: DatabaseHost, received: ReceivedRecordStore): SyncRepository {
        val vault = CredentialVault(context)
        val store = SyncStore(host, received, vault)
        val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return SyncEngine(vault, SyncApiFactory(allowLocalHttp = debug), store, received,
            MediaTransfer(store, File(context.cacheDir, "sync-transfer")), CredentialVault(context, "pending-sync-credentials"), StoredSyncPreferences(context))
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint { fun repository(): SyncRepository }

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, SyncWorkerEntryPoint::class.java).repository()
        return try {
            if (repository.sync(manual = false)) Result.success() else {
                when (repository.state.value.problem) {
                    SyncProblem.AUTHENTICATION, SyncProblem.INCOMPATIBLE, SyncProblem.ACCOUNT_REPLACEMENT -> Result.failure()
                    else -> Result.retry()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { Result.retry() }
    }
}

@Singleton
class SyncRuntime @Inject constructor(@param:ApplicationContext private val context: Context,
    private val host: DatabaseHost, private val repository: SyncRepository) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            try { repository.refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Retry on foreground. */ }
            host.observe { it.records().observeLocalChanges() }.distinctUntilChanged().debounce(750)
                .catch { error -> if (error is CancellationException) throw error; repository.refresh() }
                .collect { request() }
        }
        scope.launch {
            repository.state.map { it.connection != null && it.enabled }.distinctUntilChanged().collect { enabled ->
                val work = WorkManager.getInstance(context)
                if (enabled) {
                    work.enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
                    request()
                } else { work.cancelUniqueWork("sync-periodic"); work.cancelUniqueWork("sync-on-demand") }
            }
        }
    }
    override fun onStart(owner: LifecycleOwner) { request() }
    fun request() {
        if (!repository.state.value.enabled || repository.state.value.connection == null) return
        WorkManager.getInstance(context).enqueueUniqueWork("sync-on-demand", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
    }
}
