package imehc.recoow.core.sync

import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.database.NotificationJob
import kotlinx.coroutines.CancellationException

/** 各功能注册幂等处理器；暂不支持的任务保持持久化，直到可执行为止。 */
fun interface NotificationHandler { suspend fun deliver(job: NotificationJob): Boolean }

class NotificationDelivery(private val host: DatabaseHost, private val handler: NotificationHandler) {
    suspend fun drain(): Int {
        val token = host.sessionToken()
        val jobs = host.access(token) { it.records().notifications() }
        var delivered = 0
        for (job in jobs) {
            try {
                if (handler.deliver(job)) {
                    host.access(token) { it.records().delivered(job.tableName, job.entityId, job.version) }
                    delivered++
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Notification permission or scheduling failure must not roll back synced data. */ }
        }
        return delivered
    }
}
