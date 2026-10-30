package imehc.recoow.feature.reminders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.PreferencesRepository
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.media.ImageProcessor
import java.time.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RemindersState(val records: List<Reminder> = emptyList(), val preferences: AppPreferences? = null,
    val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val error: Int? = null,
    val now: Long = System.currentTimeMillis(), val zone: ZoneId = ZoneId.systemDefault(), val feedback: Int = 0)
@HiltViewModel
class RemindersViewModel @Inject constructor(private val repository: RemindersRepository, val media: MediaRepository,
    val processor: ImageProcessor, private val preferences: PreferencesRepository,
    private val notifications: ReminderNotifications, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(RemindersState())
    val state = mutable.asStateFlow()
    val notificationState = notifications.state
    val draft = saved.getStateFlow<ReminderDraft?>("reminderDraft", null)
    val detailId = saved.getStateFlow<String?>("reminderDetail", null)
    val makeupId = saved.getStateFlow<String?>("reminderMakeup", null)
    val tab = saved.getStateFlow("reminderTab", 0)
    private var observation: Job? = null
    private var order = emptyList<String>()
    private var orderDay: LocalDate? = null
    init { retry() }
    fun retry() {
        observation?.cancel(); mutable.update { it.copy(loading = true, failed = false, error = null) }
        observation = viewModelScope.launch {
            try { combine(repository.reminders, preferences.preferences) { r, p -> r to p }.collect { (records, prefs) ->
                mutable.update { it.copy(records = records, preferences = prefs, loading = false) }
                if (order.isEmpty()) settle()
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true, error = R.string.reminder_failed) } }
        }
    }
    fun refreshTime() {
        val zone = ZoneId.systemDefault(); val now = System.currentTimeMillis()
        mutable.update { it.copy(now = now, zone = zone) }
        if (Instant.ofEpochMilli(now).atZone(zone).toLocalDate() != orderDay) settle()
    }
    fun settle() {
        val s = state.value
        orderDay = Instant.ofEpochMilli(s.now).atZone(s.zone).toLocalDate()
        order = s.records.sortedWith(compareBy<Reminder> {
            if (it.canCheckIn(s.now, s.zone)) 0 else if (orderDay in it.dates) 1 else 2
        }.thenByDescending { it.scheduledAt }.thenByDescending { it.createdAt }.thenBy { it.id }).map { it.id }
    }
    fun rows(s: RemindersState, tab: Int): List<Reminder> {
        val filtered = s.records.filter { when (tab) { 1 -> !it.completed(s.zone) && it.paused; 2 -> it.completed(s.zone); else -> !it.completed(s.zone) && !it.paused } }
        return if (tab == 2) filtered.sortedByDescending { it.completedAt ?: it.endedAt ?: it.updatedAt }
        else filtered.sortedBy { order.indexOf(it.id).takeIf { rank -> rank >= 0 } ?: -1 }
    }
    fun tab(value: Int) { saved["reminderTab"] = value; settle() }
    fun show(id: String) { saved["reminderDraft"] = null; saved["reminderMakeup"] = null; saved["reminderDetail"] = id }
    fun back() { if (!state.value.busy) when { makeupId.value != null -> saved["reminderMakeup"] = null; draft.value != null -> saved["reminderDraft"] = null; else -> { saved["reminderDetail"] = null; settle() } } }
    fun edit(r: Reminder? = null) { saved["reminderDraft"] = r?.let(ReminderDraft::from) ?: ReminderDraft() }
    fun duplicate(r: Reminder) {
        val clock = System.currentTimeMillis()
        saved["reminderDraft"] = ReminderDraft.from(r).copy(id = java.util.UUID.randomUUID().toString(), revision = null,
            scheduledAt = clock, importedDays = "0", imageSourceId = r.id,
            endAt = r.endAt?.let { clock + (it - r.scheduledAt).coerceAtLeast(0) })
    }
    fun update(value: ReminderDraft) { if (!state.value.busy) saved["reminderDraft"] = value }
    fun save() = action { val value = requireNotNull(draft.value); val id = repository.save(value); saved["reminderDraft"] = null; saved["reminderDetail"] = id; value.imageFile?.let { java.io.File(it).delete() } }
    fun act(r: Reminder, operation: ReminderAction, reason: String = "") = action {
        repository.act(r.id, operation, reason)
        if (operation == ReminderAction.CHECK_IN) mutable.update { it.copy(feedback = it.feedback + 1) }
    }
    fun makeup(r: Reminder) { saved["reminderMakeup"] = r.id }
    fun saveMakeup(id: String, dates: Set<LocalDate>, note: String) = action { repository.makeUp(id, dates, note); saved["reminderMakeup"] = null }
    fun deleteCompletion(r: Reminder, date: LocalDate) = action { repository.deleteCompletion(r.id, date) }
    fun delete(r: Reminder) = action { repository.delete(r.id, r.revision); saved["reminderDetail"] = null }
    fun refreshNotifications() { viewModelScope.launch { notifications.refreshAccess(); notifications.requestReconcile() } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block(); notifications.requestReconcile(); notifications.refreshAccess() }
            catch (e: CancellationException) { throw e }
            catch (_: StaleReminderException) { mutable.update { it.copy(error = R.string.reminder_stale) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(error = R.string.reminder_invalid_action) } }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.reminder_failed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
