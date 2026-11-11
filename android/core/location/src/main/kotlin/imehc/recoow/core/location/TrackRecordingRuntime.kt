package imehc.recoow.core.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import imehc.recoow.core.domain.location.LocationObservation
import imehc.recoow.core.domain.tracks.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RecordingProblem { PERMISSION, PROVIDER, SAVE, INTERRUPTED, START, CHANGED }
data class RecordingState(val current: Track? = null, val latest: TrackPoint? = null, val duration: Long = 0,
    val running: Boolean = false, val busy: Boolean = false, val problem: RecordingProblem? = null, val finishedId: String? = null,
    val pointCount: Int = 0)

/** 采样由服务持有，Compose 页面及其 ViewModel 都不负责记录生命周期。 */
@Singleton
class TrackRecordingRuntime @Inject constructor(@param:ApplicationContext private val context: Context, private val repository: TracksRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex(); private var initialized = false
    private val mutable = MutableStateFlow(RecordingState()); val state = mutable.asStateFlow()
    private var session: TrackSession? = null; private var samples: Job? = null; private var ticker: Job? = null
    private val pending = mutableListOf<TrackPoint>(); private var startedElapsed = 0L; private var lastFlush = 0L
    private var frozenElapsed: Long? = null
    fun initialize() { scope.launch { lock.withLock { recover() } } }
    private suspend fun recover() {
        if (initialized) return
        try {
            val current = repository.recover()
            val detail = current?.let { repository.detail(it.id).first() }
            initialized = true
            mutable.value = RecordingState(current, detail?.points?.lastOrNull(), current?.duration ?: 0,
                problem = if (current == null) null else RecordingProblem.INTERRUPTED, pointCount = detail?.points?.size ?: 0)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(problem = RecordingProblem.SAVE) } }
    }
    fun requestStart(name: String, accuracy: Int) {
        try { context.startForegroundService(Intent(context, TrackRecordingService::class.java).setAction(TrackRecordingService.START)
            .putExtra("name", name).putExtra("accuracy", accuracy)) }
        catch (_: Exception) { mutable.update { it.copy(problem = RecordingProblem.START) } }
    }
    suspend fun prepareMaintenance() {
        pause()
        lock.withLock { check(session == null) { "Save the active track before changing local data" } }
    }
    suspend fun databaseChanged() = lock.withLock { expired(); recover() }
    fun requestPause() { scope.launch { pause() } }
    fun requestFinish() { scope.launch { finish() } }
    fun refresh() { scope.launch { lock.withLock { if (session == null) { initialized = false; recover() } } } }
    fun dismissFinished() { mutable.update { it.copy(finishedId = null) } }
    suspend fun start(name: String, accuracy: Int) = lock.withLock {
        recover(); if (!initialized || session != null) return@withLock
        if (!hasPermission()) { mutable.update { it.copy(problem = RecordingProblem.PERMISSION) }; return@withLock }
        mutable.update { it.copy(busy = true) }
        try {
            val current = mutable.value.current
            val lease = repository.begin(name, current?.accuracy ?: accuracy, current?.id)
            session = lease; pending.clear(); frozenElapsed = null; startedElapsed = SystemClock.elapsedRealtime(); lastFlush = startedElapsed
            mutable.update { it.copy(current = lease.track, latest = if (current == null) null else it.latest, pointCount = if (current == null) 0 else it.pointCount,
                running = true, busy = false, problem = null, finishedId = null, duration = lease.track.duration) }
            samples = scope.launch {
                try { AndroidLocationSource(context).observe(1000, lease.track.accuracy.toFloat()).collect { observation ->
                    when (observation) {
                        is LocationObservation.Fix -> lock.withLock {
                            if (session != lease) return@withLock
                            val p = TrackPoint.from(observation.location)
                            if (p.time < lease.start || p.time > System.currentTimeMillis() + 60_000 || p.time <= (mutable.value.latest?.time ?: Long.MIN_VALUE)) return@withLock
                            pending += p; mutable.update { it.copy(latest = p, pointCount = it.pointCount + 1, problem = null) }
                            if (pending.size >= 5) flushSafely()
                        }
                        LocationObservation.PermissionRequired -> fail(RecordingProblem.PERMISSION)
                        LocationObservation.ProviderUnavailable -> mutable.update { it.copy(problem = RecordingProblem.PROVIDER) }
                    }
                } } catch (e: CancellationException) { throw e } catch (_: Exception) { fail(RecordingProblem.PROVIDER) }
            }
            ticker = scope.launch { while (isActive) {
                delay(1000)
                if (!hasPermission()) { fail(RecordingProblem.PERMISSION); break }
                lock.withLock {
                    if (session == null) return@withLock
                    mutable.update { it.copy(duration = elapsed()) }
                    if (SystemClock.elapsedRealtime() - lastFlush >= if (pending.isEmpty()) 10_000 else 2_000) flushSafely()
                }
            } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(busy = false, problem = RecordingProblem.START) } }
    }
    private fun elapsed() = (session?.track?.duration ?: mutable.value.duration) + (((frozenElapsed ?: SystemClock.elapsedRealtime()) - startedElapsed) / 1000).coerceAtLeast(0)
    private suspend fun flush(status: TrackStatus = TrackStatus.RECORDING): Track {
        val lease = requireNotNull(session)
        val saved = repository.checkpoint(lease, pending.toList(), elapsed(), status)
        pending.clear(); lastFlush = SystemClock.elapsedRealtime()
        mutable.update { it.copy(current = saved, duration = saved.duration, pointCount = saved.pointCount) }; return saved
    }
    private suspend fun flushSafely() {
        try { flush() } catch (e: CancellationException) { throw e } catch (_: TrackSessionExpiredException) { expired() } catch (_: Exception) {
            frozenElapsed = SystemClock.elapsedRealtime(); samples?.cancel(); ticker?.cancel(); mutable.update { it.copy(problem = RecordingProblem.SAVE) }
            // 保留租约与待提交采样，让暂停、结束操作可以重试失败的事务。
        }
    }
    private fun expired() {
        samples?.cancel(); ticker?.cancel(); session = null; pending.clear(); initialized = false
        mutable.value = RecordingState(problem = RecordingProblem.CHANGED)
    }
    private suspend fun fail(problem: RecordingProblem) { pause(); mutable.update { it.copy(problem = problem) } }
    suspend fun pause() = stop(false)
    suspend fun finish() = stop(true)
    private suspend fun stop(finish: Boolean) = lock.withLock {
        recover(); val lease = session
        if (lease == null) {
            if (finish) try { mutable.value.current?.let { current ->
                val saved = repository.finishPaused(current.id); mutable.value = RecordingState(finishedId = saved.id)
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(problem = RecordingProblem.SAVE) } }
            return@withLock
        }
        samples?.cancel(); samples = null; ticker?.cancel(); ticker = null
        if (frozenElapsed == null) frozenElapsed = SystemClock.elapsedRealtime()
        mutable.update { it.copy(busy = true) }
        try {
            // 回调可能正在停止自身的采集任务，因此提交必须一次性完成。
            withContext(NonCancellable) {
                val saved = flush(if (finish) TrackStatus.FINISHED else TrackStatus.PAUSED)
                session = null
                mutable.update { it.copy(current = if (finish) null else saved, running = false, busy = false, problem = null, finishedId = if (finish) saved.id else null) }
            }
        } catch (e: CancellationException) { throw e } catch (_: TrackSessionExpiredException) { expired() } catch (_: Exception) { mutable.update { it.copy(busy = false, problem = RecordingProblem.SAVE) } }
    }
    fun serviceDestroyed() { scope.launch { pause() } }
    private fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
