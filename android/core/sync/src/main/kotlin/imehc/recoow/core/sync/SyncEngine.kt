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
    private val pendingCredentials: CredentialStore, private val preferences: SyncPreferences) : SyncRepository, AccountSecurityRepository {
    private val mutex = Mutex()
    private val toggleMutex = Mutex()
    @Volatile private var activeSync: Job? = null
    @Volatile private var disabling = false
    private val mutableState = MutableStateFlow(SyncState())
    override val state = mutableState.asStateFlow()
    private val mutableAccountState = MutableStateFlow(AccountSecurityState())
    override val accountState = mutableAccountState.asStateFlow()
    private var accountConnectionId: String? = null

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
                        accountConnectionId = null
                        mutableAccountState.value = AccountSecurityState(loaded = true)
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
        try {
            if (!enabled) { disabling = true; activeSync?.cancelAndJoin() }
            mutex.withLock {
                try {
                    val connection = requireNotNull(credentials.load())
                    preferences.setEnabled(connection, enabled)
                    refreshState()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { mutableState.update { it.copy(problem = problem(e)) } }
            }
        } finally { disabling = false }
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
        if (id == connection.serverDevice) clearConnection()
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
        if (accountConnectionId != connection?.connectionId) {
            accountConnectionId = connection?.connectionId
            mutableAccountState.value = AccountSecurityState()
        }
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

    override suspend fun connect(endpoint: String, method: ConnectionMethod, secret: String, deviceName: String, username: String) =
        withContext(Dispatchers.IO) { mutex.withLock {
            mutableState.update { it.copy(running = true, problem = null) }
            var issued: Credentials? = null
            try {
                val normalized = apiProvider.endpoint(endpoint)
                if (method == ConnectionMethod.CREATE && (credentials.load() != null || store.hasDatasetBinding())) throw AccountReplacementRequired()
                val public = apiProvider.create(normalized)
                // 注册完成后先记入日志，再执行任何数据集或备份操作。
                val staged = pendingCredentials.load()?.takeIf { it.endpoint == normalized && method == ConnectionMethod.CREATE }
                val fresh = staged ?: run {
                    val path = when (method) { ConnectionMethod.CREATE -> "api/accounts"; ConnectionMethod.PAIR -> "api/device-pairings"; ConnectionMethod.RECOVER -> "api/account-recoveries"; ConnectionMethod.LOGIN -> "api/device-logins" }
                    val body = buildJsonObject {
                        when (method) { ConnectionMethod.CREATE -> put("bootstrapToken", secret)
                            ConnectionMethod.PAIR -> put("code", secret)
                            ConnectionMethod.RECOVER -> put("recoveryKey", secret)
                            ConnectionMethod.LOGIN -> { put("username", username.trim()); put("password", secret) } }
                        if (method != ConnectionMethod.CREATE) put("deviceName", deviceName.trim().ifBlank { "Android" })
                    }
                    val result = if (method == ConnectionMethod.LOGIN) {
                        val login = AccountApi(public).login(username, secret, deviceName)
                        buildJsonObject { put("accountId", login.accountId); put("deviceId", login.deviceId); put("deviceToken", login.deviceToken) }
                    } else decodeObject(public.post(path, body))
                    Credentials(endpointHash(normalized) + ":" + UUID.randomUUID(), normalized, result.text("accountId"),
                        result.text("deviceId"), result.text("deviceToken"),
                        if (method == ConnectionMethod.CREATE) result.text("recoveryKey") else null, method in setOf(ConnectionMethod.CREATE, ConnectionMethod.RECOVER)).also { issued = it; pendingCredentials.save(it) }
                }
                issued = fresh
                val candidate = store.alignConnection(fresh)
                val api = apiProvider.create(candidate.endpoint, candidate.token)
                val caps = capabilities(api, candidate)
                if (method == ConnectionMethod.LOGIN && !caps.accountCredentials) throw SyncHttpException(404)
                val resetRequired = try { store.prepare(candidate, caps); false }
                    catch (_: CloudResetConfirmationRequired) { true }
                preferences.setEnabled(candidate, !resetRequired && method != ConnectionMethod.CREATE)
                credentials.save(candidate)
                issued = null
                pendingCredentials.save(null)
                refreshState()
                if (resetRequired) mutableState.update { it.copy(problem = SyncProblem.RESET) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(problem = problem(error)) }; refreshState()
            } finally {
                if (method != ConnectionMethod.CREATE) issued?.let { rejected -> withContext(NonCancellable) {
                    withTimeoutOrNull(3000) { runCatching { apiProvider.create(rejected.endpoint, rejected.token).delete("api/devices/" + rejected.serverDevice) } }
                    pendingCredentials.save(null)
                } }
                mutableState.update { it.copy(running = false) }
            }
        } }

    override suspend fun disconnect() = withContext(Dispatchers.IO) { toggleMutex.withLock {
        disabling = true
        try {
            activeSync?.cancelAndJoin()
            mutex.withLock {
                clearConnection()
            }
        } finally { disabling = false }
    } }

    /** 本地退出登录会保留同一账户的数据集绑定与待提交编辑。 */
    private fun clearConnection() {
        credentials.load()?.let { preferences.setEnabled(it, false) }
        credentials.save(null); pendingCredentials.save(null)
        accountConnectionId = null
        mutableAccountState.value = AccountSecurityState(loaded = true)
        mutableState.value = SyncState(loaded = true)
    }

    override suspend fun confirmCloudReset() = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            val connection = requireNotNull(credentials.load())
            store.prepare(connection, capabilities(apiProvider.create(connection.endpoint, connection.token), connection), allowGenerationChange = true)
            preferences.setEnabled(connection, true)
            mutableState.update { it.copy(problem = null) }
            refreshState()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(problem = problem(error)) } }
    } }

    override suspend fun refreshAccount() = accountOperation { connection ->
        if (connection == null) {
            mutableAccountState.value = AccountSecurityState(loaded = true)
        } else {
            val api = apiProvider.create(connection.endpoint, connection.token)
            val caps = capabilities(api, connection)
            if (!caps.accountCredentials) mutableAccountState.update { it.copy(loaded = true, supported = false, username = null) }
            else {
                val identity = AccountApi(api).identity()
                require(identity == null || identity.accountId == connection.account)
                mutableAccountState.update { it.copy(loaded = true, supported = true, username = identity?.username) }
            }
            val webEditing = if (connection.canPair) AccountApi(api).webAccess() else null
            mutableAccountState.update { it.copy(webEditing = webEditing) }
        }
    }

    override suspend fun setAccountPassword(password: String) = accountOperation { current ->
        require(AccountPasswordRules.valid(password))
        val connection = requireNotNull(current)
        if (!connection.canPair) throw SyncHttpException(403)
        val api = apiProvider.create(connection.endpoint, connection.token)
        if (!capabilities(api, connection).accountCredentials) throw SyncHttpException(404)
        val identity = AccountApi(api).bind(password)
        require(identity.accountId == connection.account)
        mutableAccountState.update { it.copy(loaded = true, supported = true, username = identity.username, result = AccountResult.PASSWORD_SET) }
    }

    override suspend fun setWebEditing(enabled: Boolean) = accountOperation { current ->
        val connection = requireNotNull(current)
        if (!connection.canPair) throw SyncHttpException(403)
        val saved = AccountApi(apiProvider.create(connection.endpoint, connection.token)).setWebAccess(enabled)
        mutableAccountState.update { it.copy(webEditing = saved, result = AccountResult.WEB_ACCESS_UPDATED) }
    }

    override suspend fun changeAccountPassword(current: String, next: String) = accountOperation { selected ->
        require(AccountPasswordRules.valid(current) && AccountPasswordRules.valid(next))
        val connection = requireNotNull(selected)
        val api = apiProvider.create(connection.endpoint, connection.token)
        if (!capabilities(api, connection).accountCredentials) throw SyncHttpException(404)
        AccountApi(api).change(current, next)
        mutableAccountState.update { it.copy(result = AccountResult.PASSWORD_CHANGED) }
    }

    override suspend fun resetAccountPassword(endpoint: String, recoveryKey: String, next: String) = accountOperation { connection ->
        require(AccountPasswordRules.valid(next))
        val normalized = apiProvider.endpoint(endpoint)
        // 密钥重设不附带当前设备 token，也不切换本机账号或同步范围。
        val identity = AccountApi(apiProvider.create(normalized)).reset(recoveryKey, next)
        mutableAccountState.update { it.copy(resetUsername = identity.username, result = AccountResult.PASSWORD_RESET,
            username = if (connection?.endpoint == normalized && connection.account == identity.accountId) identity.username else it.username) }
    }

    private suspend fun accountOperation(block: suspend (Credentials?) -> Unit) = withContext(Dispatchers.IO) { mutex.withLock {
        mutableState.update { it.copy(running = true) }
        mutableAccountState.update { it.copy(busy = true, problem = null, result = null, resetUsername = null) }
        try {
            val connection = credentials.load()
            if (accountConnectionId != connection?.connectionId) {
                accountConnectionId = connection?.connectionId
                mutableAccountState.value = AccountSecurityState(busy = true)
            }
            block(connection)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = when (error) {
                is SyncHttpException -> when (error.status) {
                    400 -> AccountProblem.PASSWORD
                    401 -> AccountProblem.AUTHENTICATION
                    403 -> AccountProblem.PERMISSION
                    404 -> AccountProblem.UNSUPPORTED
                    409 -> AccountProblem.CONFLICT
                    429 -> AccountProblem.RATE_LIMIT
                    else -> AccountProblem.CONNECTION
                }
                is SyncEndpointException -> AccountProblem.CONNECTION
                is IllegalArgumentException -> AccountProblem.PASSWORD
                is IOException -> AccountProblem.CONNECTION
                else -> AccountProblem.STORAGE
            }
            mutableAccountState.update { it.copy(loaded = true, problem = failure) }
        } finally {
            mutableAccountState.update { it.copy(busy = false) }
            mutableState.update { it.copy(running = false) }
        }
    } }

    override suspend fun acknowledgeRecoveryKey() = withContext(Dispatchers.IO) { mutex.withLock {
        credentials.load()?.let { credentials.save(it.copy(recoveryKey = null)) }
        pendingCredentials.load()?.let { pendingCredentials.save(it.copy(recoveryKey = null)) }
        credentials.load()?.let { preferences.setEnabled(it, true) }
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
            // 续传的原始分页会先重放，再重新请求相同的位置。
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
                    // 被拒绝的依赖行可在剩余批次与下行拉取完成后重试。
                    if (receipts.none { it.status in setOf("applied", "duplicate", "conflict") }) break
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { outboundProblem = error }
            // 冲突、非法的上行数据和上传失败都不能阻塞下行变更。
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
            if (error is CloudResetConfirmationRequired || error is SyncHttpException && error.status == 401) {
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
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: CloudResetConfirmationRequired) {
                    credentials.load()?.let { preferences.setEnabled(it, false) }
                    mutableState.update { it.copy(enabled = false, problem = SyncProblem.RESET) }
                } catch (_: Exception) { /* The session was replaced. */ }
            }
            false
        } finally {
            activeSync = null
            mutableState.update { it.copy(running = false, loaded = true, stage = SyncStage.IDLE) }
            mutex.unlock()
        }
    }

    private suspend fun capabilities(api: SyncApi, connection: Credentials): Capabilities {
        val caps = Capabilities.decode(decodeObject(api.get("api/sync/capabilities")), connection.account, store.contracts)
        if (caps.deviceProfiles) store.host.clientSource?.profile()?.let { api.put("api/devices/current/profile", it) }
        return caps
    }

    private suspend fun receivePages(session: SyncSession, api: SyncApi, caps: Capabilities, bootstrapOnly: Boolean) {
        repeat(100) {
            val target = store.target(session)
            if (bootstrapOnly && target.bootstrapComplete) return
            val prefix = if (target.bootstrapComplete) "pull:${target.receivedCursor}:" else "bootstrap:${target.bootstrapAfter}:"
            val previous = store.access(session) { db -> db.records().unreceivedPages(session.scope).firstOrNull { it.pageKey.startsWith(prefix) } }
            val key = previous?.pageKey ?: prefix + UUID.randomUUID()
            val bytes = previous?.body ?: if (!target.bootstrapComplete && caps.manifestBootstrap) {
                val manifestBytes = api.get("api/sync/manifests", query(target, caps.pullLimit))
                received.persistRaw(session.scope, session.databaseToken, "manifest:${target.bootstrapAfter}:$key", manifestBytes)
                val manifest = decodeObject(manifestBytes)
                val entries = SyncManifest.entries(manifest, target.generation, target.bootstrapWatermark, target.bootstrapAfter)
                val seeds = store.manifestSeeds(session, entries)
                val versions = entries.map { it.integer("version") }.filter { it !in seeds }
                val snapshots = if (versions.isEmpty()) null else decodeObject(api.post("api/sync/snapshot-queries", buildJsonObject {
                    put("generation", target.generation); put("watermark", target.bootstrapWatermark)
                    put("versions", JsonArray(versions.map(::JsonPrimitive)))
                }))
                SyncManifest.hydrate(manifest, entries, seeds.mapValues { it.value.second },
                    seeds.mapValues { it.value.first.mutationId }, snapshots).toString().encodeToByteArray()
            } else api.get(if (target.bootstrapComplete) "api/sync/changes" else "api/sync/snapshots", query(target, caps.pullLimit))
            // 先落盘字节，再解析 JSON、行契约或游标。
            store.access(session) { }
            received.persistRaw(session.scope, session.databaseToken, key, bytes)
            val page = SyncProtocol.page(decodeObject(bytes), target.generation,
                if (target.bootstrapComplete) target.receivedCursor else target.bootstrapAfter,
                if (target.bootstrapComplete) null else target.bootstrapWatermark)
            val cursor = if (target.bootstrapComplete || !page.more) page.cursor else target.receivedCursor
            received.receivePage(session.scope, session.databaseToken, key, bytes, page.entries.map { it.toString() },
                if (!target.bootstrapComplete && !page.more) target.bootstrapWatermark else maxOf(cursor, page.entries.lastOrNull()?.integer("version") ?: cursor),
                if (target.bootstrapComplete) null else page.cursor, !target.bootstrapComplete && !page.more,
                beforeReceive = { db -> if (!target.bootstrapComplete) store.matchManifestSeeds(db, session.scope, decodeObject(bytes)) })
            mutableState.update { it.copy(stage = SyncStage.APPLYING) }
            received.applyPending(session.scope, session.databaseToken)
            mutableState.update { it.copy(stage = SyncStage.DOWNLOADING) }
            store.access(session) { db ->
                db.records().removeReceivedPage(session.scope, key)
                db.openHelper.writableDatabase.execSQL("DELETE FROM sync_received_pages WHERE scope = ? AND pageKey LIKE ?", arrayOf(session.scope, "manifest:${target.bootstrapAfter}:%"))
            }
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
            val caps = Capabilities.decode(capabilities, connection.account, store.contracts)
            require(capabilities.getValue("features").jsonArray.any { it.jsonPrimitive.content == "conflict_resolution" }) { "Unsupported conflict resolution protocol; update backend" }
            val scope = store.host.access { requireNotNull(it.records().activeScope()) }
            val session = SyncSession(connection, scope, store.host.sessionToken(), caps.mutationOrigin)
            store.resolve(session, table, id, remoteVersion, keepLocal,
                requireNotNull(state.value.conflicts.find { it.table == table && it.id == id && it.remoteVersion == remoteVersion }).localSnapshot)
            refreshState(session)
            mutableState.update { it.copy(problem = null) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { mutableState.update { it.copy(problem = problem(error)) } }
    } }

    private fun problem(error: Exception): SyncProblem = when (error) {
        is AccountReplacementRequired -> SyncProblem.ACCOUNT_REPLACEMENT
        is CloudResetConfirmationRequired -> SyncProblem.RESET
        is SyncEndpointException -> SyncProblem.SERVER_ADDRESS
        is SyncHttpException -> when {
            error.resetRequired -> SyncProblem.RESET
            error.status in setOf(401, 403) -> SyncProblem.AUTHENTICATION
            error.status == 429 -> SyncProblem.RATE_LIMIT
            error.status == 404 -> SyncProblem.INCOMPATIBLE
            else -> SyncProblem.CONNECTION
        }
        is IOException -> SyncProblem.CONNECTION
        is IllegalArgumentException -> SyncProblem.INCOMPATIBLE
        else -> SyncProblem.STORAGE
    }

    companion object {
        fun endpointHash(endpoint: String): String = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
