package imehc.recoow.core.sync

import android.content.Context

/** Connection preferences are installation-local and keyed by normalized server plus account. */
interface SyncPreferences {
    fun clear() {}
    fun enabled(connection: Credentials): Boolean
    fun setEnabled(connection: Credentials, value: Boolean)
    fun lastSuccess(connection: Credentials): Long?
    fun setLastSuccess(connection: Credentials, value: Long)
}
class StoredSyncPreferences(context: Context) : SyncPreferences {
    private val store = context.getSharedPreferences("sync-preferences", Context.MODE_PRIVATE)
    override fun clear() { check(store.edit().clear().commit()) }
    private fun key(c: Credentials) = SyncEngine.endpointHash(c.endpoint + "\n" + c.account)
    override fun enabled(connection: Credentials) = store.getBoolean("enabled:${key(connection)}", false)
    override fun setEnabled(connection: Credentials, value: Boolean) { check(store.edit().putBoolean("enabled:${key(connection)}", value).commit()) }
    override fun lastSuccess(connection: Credentials) = store.getLong("success:${key(connection)}", 0).takeIf { it > 0 }
    override fun setLastSuccess(connection: Credentials, value: Long) { check(store.edit().putLong("success:${key(connection)}", value).commit()) }
}
