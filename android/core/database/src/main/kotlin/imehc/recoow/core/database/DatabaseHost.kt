package imehc.recoow.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.database.generated.DATABASE_VERSION
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 仅在排除数据库、媒体、偏好等全部写入方且 Room 已关闭时才调用。 */
interface DatabaseRecovery {
    suspend fun beforeOpen(version: Int)
    suspend fun opened()
    suspend fun failed()
    suspend fun begin(reason: String): File
    suspend fun commit()
}

class DatabaseHost(
    context: Context,
    val directory: File,
    private val recovery: DatabaseRecovery,
    private val migrations: List<Migration> = DatabaseMigrations.all,
    val clientSource: LocalClientSource? = null,
) {
    private val context = context.applicationContext
    private val gate = Mutex()
    private val current = MutableStateFlow<RecoowDatabase?>(null)
    private var opened: RecoowDatabase? = null
    private var session = 0L
    val databaseFile get() = File(directory, "records.sqlite")
    val mediaDirectory get() = File(directory, "media")

    suspend fun <T> access(block: suspend (RecoowDatabase) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock { block(open()) }
    }

    suspend fun sessionToken(): Long = access { session }
    suspend fun <T> access(token: Long, block: suspend (RecoowDatabase) -> T): T = access {
        check(token == session) { "Stale database session" }
        block(it)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(query: (RecoowDatabase) -> Flow<T>): Flow<T> = flow {
        access { }
        emitAll(current.flatMapLatest { database -> if (database == null) emptyFlow() else query(database) })
    }

    /** 内部维护边界：传入的动作只能使用给定的数据库与媒体根目录。 */
    suspend fun <T> guarded(reason: String, action: suspend (RecoowDatabase, File) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock {
            open()
            closeCurrent()
            recovery.begin(reason) // A failed backup never starts the action.
            try {
                val database = build()
                val result = action(database, mediaDirectory)
                closeCurrent()
                // 先校验结果，再清除恢复标记或将其发布给读取方。
                val validated = build()
                validated.openHelper.writableDatabase.query("PRAGMA integrity_check(1)").use {
                    check(it.moveToFirst() && it.getString(0) == "ok") { "Invalid database after maintenance" }
                }
                validated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                    check(!it.moveToFirst()) { "Invalid references after maintenance" }
                }
                recovery.commit()
                current.value = validated
                result
            } catch (error: Throwable) {
                // 回滚不响应取消，避免中断后暴露只恢复了一半的数据。
                withContext(kotlinx.coroutines.NonCancellable) {
                    closeCurrent()
                    try { recovery.failed(); open() } catch (rollback: Throwable) { error.addSuppressed(rollback) }
                }
                throw error
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { gate.withLock { closeCurrent() } }

    private suspend fun open(): RecoowDatabase {
        opened?.let { return it }
        recovery.beforeOpen(VERSION)
        return try {
            build().also { recovery.opened(); current.value = it }
        } catch (error: Throwable) {
            closeCurrent()
            withContext(kotlinx.coroutines.NonCancellable) {
                try { recovery.failed() } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            }
            throw error
        }
    }

    private fun build(): RecoowDatabase {
        check(directory.isDirectory || directory.mkdirs())
        val database = Room.databaseBuilder(context, RecoowDatabase::class.java, databaseFile.path)
            .addMigrations(*migrations.toTypedArray())
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try { database.openHelper.writableDatabase } catch (error: Throwable) { database.close(); throw error }
        session++
        opened = database
        return database
    }

    private fun closeCurrent() {
        val database = opened
        opened = null
        current.value = null
        session++
        database?.close()
    }

    companion object { const val VERSION = DATABASE_VERSION }
}
