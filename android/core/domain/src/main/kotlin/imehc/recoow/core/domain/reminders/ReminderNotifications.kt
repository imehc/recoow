package imehc.recoow.core.domain.reminders

import kotlinx.coroutines.flow.StateFlow

data class ReminderNotificationState(val allowed: Boolean = true, val exact: Boolean = false, val failed: Boolean = false)
interface ReminderNotifications {
    val state: StateFlow<ReminderNotificationState>
    fun requestReconcile()
    suspend fun refreshAccess()
}
