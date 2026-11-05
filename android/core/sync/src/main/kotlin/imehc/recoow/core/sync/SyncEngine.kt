package imehc.recoow.core.sync

import androidx.room.withTransaction
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.database.SyncTarget
import imehc.recoow.core.domain.sync.*
import imehc.recoow.core.network.*
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class SyncEngine(private val credentials: CredentialStore, private val apiProvider: SyncApiProvider,
    private val store: SyncStore, private val received: ReceivedRecordStore, private val media: MediaTransfer,
    private val pendingCredentials: CredentialStore, private val preferences: SyncPreferences) : SyncRepository {
    private val mutex = Mutex()
    private val toggleMutex = Mutex()
    @Volatile private var activeSync: Job? = null
    @Volatile private var disabling = false
    private val mutableState = MutableStateFlow(SyncState())
    override val state = mutableState.asStateFlow()

    override suspend fun <T> localMaintenance(clearCredentials: Boolean, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        toggleMutex.withLock {
            disabling = true
            try {
                activeSync?.cancelAndJoin()
                mutex.withLock {
                    val result = action()
                    if (clearCredentials) withContext(NonCancellable) {
                        preferences.clear(); credentials.save(null); pendingCredentials.save(null)
                        mutableState.value = SyncState(loaded = true)
                    }
                    else try { refreshState() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutableState.update { it.copy(problem = SyncProblem.STORAGE) } }
                    result
                }
            } finally { disabling = false }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { toggleMutex.withLock {
        if (!enabled) { disabling = true; activeSync?.cancelAndJoin() }
        mutex.withLock {
            try {
                val connection = requireNotNull(credentials.load())
                preferences.setEnabled(connection, enabled)
                disabling = false
                refreshState()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutableState.update { it.copy(problem = problem(e)) } }
        }
    } }

    override suspend fun refreshDevices() = deviceOperation { connection, api ->
        mutableState.update { it.copy(devices = decodeObject(api.get("api/devices"))["devices"]!!.jsonArray.map { value ->
            val device = value.jsonObject
            SyncDevice(device.text("id"), device.text("name"), device["isOwner"]!!.jsonPrimitive.boolean, device.text("lastSeenAt"))
        }) }
    }
    override suspend fun revokeDevice(id: String) = deviceOperation { connection, api ->
        require(id.isNotBlank())
        require(if (connection.canPair) id != connection.serverDevice else id == connection.serverDevice)
        api.delete("api/devices/" + java.net.URLEncoder.encode(id, "UTF-8"))
        if (id == connection.serverDevice) { credentials.save(null); mutableState.value = SyncState(loaded = true) }
        else mutableState.update { it.copy(devices = it.devices.filterNot { device -> device.id == id }) }
    }
    private suspend fun deviceOperation(block: suspend (Credentials, SyncApi) -> Unit) = withContext(Dispatchers.IO) { mutex.withLock {
        mutableState.update { it.copy(running = true, problem = null) }
        try { val connection = requireNotNull(credentials.load()); block(connection, apiProvider.create(connection.endpoint, connection.token)) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { mutableState.update { it.copy(problem = problem(e)) } }
        finally { mutableState.update { it.copy(running = false) } }
    } }

    override suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        try { refreshState() } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { mutableState.update { it.copy(loaded = true, problem = problem(error)) } }
    } }

    private suspend fun refreshState(session: SyncSession? = null) {
        val connection = credentials.load()
        val pending = pendingCredentials.load()
        val current = session ?: connection?.let { candidate ->
            store.host.access { db ->
                db.records().activeScope()?.let { scope -> db.records().target(scope)?.takeIf { it.connectionId == candidate.connectionId }
                    ?.let { SyncSession(candidate, scope, 0) } }
            }?.copy(databaseToken = store.host.sessionToken())
        }
        val conflicts = current?.let { store.conflicts(it) }.orEmpty()
        val counts = current?.let { active -> store.access(active) { db ->
            listOf(db.records().outbox(active.scope).count { it.status == "pending" }, db.records().inbox(active.scope).size,
                db.records().media(active.scope, "download").count { it.state == "pending" } + db.records().media(active.scope, "upload").count { it.state == "pending" },
                db.records().notifications().size)
        } } ?: listOf(0, 0, 0, 0)
        mutableState.update { it.copy(loaded = true, connection = connection?.let { c -> SyncConnection(c.endpoint, c.account, c.serverDevice, c.canPair) },
            pending = counts[0], waitingRows = counts[1], pendingMedia = counts[2], notificationJobs = counts[3], conflicts = conflicts,
            enabled = connection?.let(preferences::enabled) == true, lastSuccessAt = connection?.let(preferences::lastSuccess),
            recoveryKey = pending?.recoveryKey ?: connection?.recoveryKey) }
    }

    override suspend fun connect(endpoint: String, method: ConnectionMethod, secret: String, deviceName: String, replaceLocal: Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock {
            mutableState.update { it.copy(running = true, problem = null) }
            try {
                val normalized = apiProvider.endpoint(endpoint)
                val public = apiProvider.create(normalized)
                // A completed registration is journaled before any dataset/backup operation.
                val staged = pendingCredentials.load()?.takeIf { it.endpoint == normalized && (method == ConnectionMethod.CREATE || replaceLocal) }
                val candidate = staged ?: run {
                    val path = when (method) { ConnectionMethod.CREATE -> "api/accounts"; ConnectionMethod.PAIR -> "api/device-pairings"; ConnectionMethod.RECOVER -> "api/account-recoveries" }
                    val body = buildJsonObject {
                        when (method) { ConnectionMethod.CREATE -> put("bootstrapToken", secret)
                            ConnectionMethod.PAIR -> put("code", secret)
                            ConnectionMethod.RECOVER -> put("recoveryKey", secret) }
                        if (method != ConnectionMethod.CREATE) put("deviceName", deviceName.trim().ifBlank { "Android" })
                    }
                    val result = decodeObject(public.post(path, body))
                    Credentials(endpointHash(normalized) + ":" + UUID.randomUUID(), normalized, result.text("accountId"),
                        result.text("deviceId"), result.text("deviceToken"),
                        if (method == ConnectionMethod.CREATE) result.text("recoveryKey") else null, method != ConnectionMethod.PAIR).also { pendingCredentials.save(it) }
                }
                val api = apiProvider.create(candidate.endpoint, candidate.token)
                val caps = capabilities(api, candidate)
                store.prepare(candidate, caps, replaceLocal)
                credentials.save(candidate)
                pendingCredentials.save(null)
                refreshState()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutableState.update { it.copy(problem = problem(error)) }; refreshState()
            } finally { mutableState.update { it.copy(running = false) } }
        } }

    override suspend fun disconnect() = withContext(Dispatchers.IO) { mutex.withLock {
        // Retain the dataset binding and pending edits. Reconnecting to a different account asks for replacement.
        credentials.save(null)
        mutableState.value = SyncState(loaded = true, recoveryKey = pendingCredentials.load()?.recoveryKey)
    } }

    override suspend fun acknowledgeRecoveryKey() = withContext(Dispatchers.IO) { mutex.withLock {
        credentials.load()?.let { credentials.save(it.copy(recoveryKey = null)) }
        pendingCredentials.load()?.let { pendingCredentials.save(it.copy(recoveryKey = null)) }
        refreshState()
    } }

    override suspend fun generatePairingCode() = withContext(Dispatchers.IO) { mutex.withLock {
        mutableState.update { it.copy(running = true, problem = null) }
        try {
            val connection = requireNotNull(credentials.load())
            require(connection.canPair)
            val response = decodeObject(apiProvider.create(connection.endpoint, connection.token).post("api/device-pairing-codes", buildJsonObject { }))
            val code = response.text("code")
            require(code.matches(Regex("[0-9]{10}")))
            mutableState.update { it.copy(pairingCode = code) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { mutableState.update { it.copy(problem = problem(error)) }
        } finally { mutableState.update { it.copy(running = false) } }
    } }
    override suspend fun dismissPairingCode() { mutableState.update { it.copy(pairingCode = null) } }

    override suspend fun sync(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext false
        activeSync = currentCoroutineContext().job
        var session: SyncSession? = null
        try {
            val connection = credentials.load() ?: run { refreshState(); return@withContext true }
            if (disabling || !preferences.enabled(connection)) { refreshState(); return@withContext true }
            mutableState.update { it.copy(running = true, problem = null, stage = SyncStage.PREPARING) }
            val api = apiProvider.create(connection.endpoint, connection.token)
            val caps = capabilities(api, connection)
            session = store.prepare(connection, caps)
            if (!manual && store.target(session).nextAttemptAt > System.currentTimeMillis()) return@withContext false
            val activeScope = session.scope
            if (manual) store.access(session) { db -> db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_media_jobs SET nextAttemptAt = 0 WHERE scope = ? AND state = 'pending'", arrayOf(activeScope))
            } }
            // A resumed raw page is replayed before requesting the same position again.
            receivePages(session, api, caps, bootstrapOnly = true)
            received.applyPending(session.scope, session.databaseToken)
            mutableState.update { it.copy(stage = SyncStage.UPLOADING) }
            var outboundProblem: Exception? = null
            try {
                for (batch in 0 until 10) {
                    val mutations = store.pending(session, minOf(40, caps.pushLimit))
                    if (mutations.isEmpty()) break
                    media.transfer(session, api, "upload", caps.mediaLimit)
                    val ready = mutations.filter { media.ready(session, it) }
                    if (ready.isEmpty()) break
                    val response = api.post("api/sync/mutations", buildJsonObject {
                        put("protocolVersion", 2); put("generation", caps.generation); put("items", JsonArray(ready.map { it.request }))
                    })
                    val receipts = SyncProtocol.receipts(decodeObject(response), ready.map { it.request })
                    store.acknowledge(session, ready, receipts)
                    // Rejected dependency rows can retry after the remaining batch and inbound pull.
                    if (receipts.none { it.status in setOf("applied", "duplicate", "conflict") }) break
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { outboundProblem = error }
            // Conflicts, bad outbound data and upload failures never starve incoming changes.
            mutableState.update { it.copy(stage = SyncStage.DOWNLOADING) }
            receivePages(session, api, caps, bootstrapOnly = false)
            received.applyPending(session.scope, session.databaseToken)
            val downloaded = media.transfer(session, api, "download", caps.mediaLimit)
            refreshState(session)
            val current = mutableState.value
            val success = outboundProblem == null && downloaded && current.waitingRows == 0 && current.pending == 0 && current.pendingMedia == 0
            store.retry(session, !success)
            if (success) { preferences.setLastSuccess(connection, System.currentTimeMillis()); refreshState(session) }
            if (!success) mutableState.update { it.copy(problem = outboundProblem?.let(::problem) ?: if (!downloaded) SyncProblem.MEDIA else SyncProblem.PENDING) }
            success
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(problem = problem(error)) }
            if (error is SyncHttpException && error.status == 401) {
                credentials.load()?.let { preferences.setEnabled(it, false) }
                mutableState.update { it.copy(enabled = false) }
            }
            if (session != null) {
                try {
                    if (error is SyncHttpException && error.resetRequired) {
                        val api = apiProvider.create(session.connection.endpoint, session.connection.token)
                        session = store.restart(session, capabilities(api, session.connection))
                    }
                    store.retry(session, true); refreshState(session)
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* The session was replaced. */ }
            }
            false
        } finally {
            activeSync = null
            mutableState.update { it.copy(running = false, loaded = true, stage = SyncStage.IDLE) }
            mutex.unlock()
        }
    }

    private suspend fun capabilities(api: SyncApi, connection: Credentials): Capabilities {
        return Capabilities.decode(decodeObject(api.get("api/sync/capabilities")), connection.account, store.contracts)
    }

    private suspend fun receivePages(session: SyncSession, api: SyncApi, caps: Capabilities, bootstrapOnly: Boolean) {
        repeat(100) {
            val target = store.target(session)
            if (bootstrapOnly && target.bootstrapComplete) return
            val prefix = if (target.bootstrapComplete) "pull:${target.receivedCursor}:" else "bootstrap:${target.bootstrapAfter}:"
            val previous = store.access(session) { db -> db.records().unreceivedPages(session.scope).firstOrNull { it.pageKey.startsWith(prefix) } }
            val key = previous?.pageKey ?: prefix + UUID.randomUUID()
            val bytes = previous?.body ?: api.get(if (target.bootstrapComplete) "api/sync/changes" else "api/sync/snapshots", query(target, caps.pullLimit))
            // Store bytes before decoding JSON, row contracts, or cursors.
            store.access(session) { }
            received.persistRaw(session.scope, session.databaseToken, key, bytes)
            val page = SyncProtocol.page(decodeObject(bytes), target.generation,
                if (target.bootstrapComplete) target.receivedCursor else target.bootstrapAfter,
                if (target.bootstrapComplete) null else target.bootstrapWatermark)
            val cursor = if (target.bootstrapComplete || !page.more) page.cursor else target.receivedCursor
            received.receivePage(session.scope, session.databaseToken, key, bytes, page.entries.map { it.toString() },
                if (!target.bootstrapComplete && !page.more) target.bootstrapWatermark else maxOf(cursor, page.entries.lastOrNull()?.integer("version") ?: cursor),
                if (target.bootstrapComplete) null else page.cursor, !target.bootstrapComplete && !page.more)
            mutableState.update { it.copy(stage = SyncStage.APPLYING) }
            received.applyPending(session.scope, session.databaseToken)
            mutableState.update { it.copy(stage = SyncStage.DOWNLOADING) }
            store.access(session) { it.records().removeReceivedPage(session.scope, key) }
            if (!page.more) return
        }
        throw IOException("Sync page budget reached; resume in next job")
    }

    private fun query(target: SyncTarget, limit: Int): Map<String, String> = buildMap {
        put("generation", target.generation); put("limit", minOf(limit, 500).toString())
        if (target.bootstrapComplete) put("cursor", target.receivedCursor.toString()) else {
            put("watermark", target.bootstrapWatermark.toString()); put("after", target.bootstrapAfter.toString())
        }
    }

    override suspend fun resolve(table: String, id: String, remoteVersion: Long, keepLocal: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            val connection = requireNotNull(credentials.load())
            val capabilities = decodeObject(apiProvider.create(connection.endpoint, connection.token).get("api/sync/capabilities"))
            Capabilities.decode(capabilities, connection.account, store.contracts)
            require(capabilities.getValue("features").jsonArray.any { it.jsonPrimitive.content == "conflict_resolution" }) { "Unsupported conflict resolution protocol; update backend" }
            val scope = store.host.access { requireNotNull(it.records().activeScope()) }
            val session = SyncSession(connection, scope, store.host.sessionToken())
            store.resolve(session, table, id, remoteVersion, keepLocal,
                requireNotNull(state.value.conflicts.find { it.table == table && it.id == id && it.remoteVersion == remoteVersion }).localSnapshot)
            refreshState(session)
            mutableState.update { it.copy(problem = null) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { mutableState.update { it.copy(problem = problem(error)) } }
    } }

    private fun problem(error: Exception): SyncProblem = when (error) {
        is AccountReplacementRequired -> SyncProblem.ACCOUNT_REPLACEMENT
        is SyncEndpointException -> SyncProblem.SERVER_ADDRESS
        is SyncHttpException -> when { error.resetRequired -> SyncProblem.RESET; error.status in setOf(401, 403) -> SyncProblem.AUTHENTICATION; else -> SyncProblem.CONNECTION }
        is IOException -> SyncProblem.CONNECTION
        is IllegalArgumentException -> SyncProblem.INCOMPATIBLE
        else -> SyncProblem.STORAGE
    }

    companion object {
        fun endpointHash(endpoint: String): String = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
