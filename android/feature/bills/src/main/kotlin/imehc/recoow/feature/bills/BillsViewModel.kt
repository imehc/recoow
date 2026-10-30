package imehc.recoow.feature.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.PreferencesRepository
import imehc.recoow.core.media.ImageProcessor
import java.io.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AccountDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null,
    val name: String = "", val kind: AccountKind = AccountKind.TRANSIT, val balance: String = "", val note: String = "") : Serializable
data class BillsState(val bills: List<BillRecord> = emptyList(), val accounts: List<StoredValueAccount> = emptyList(),
    val preferences: AppPreferences? = null, val loading: Boolean = true, val busy: Boolean = false,
    val error: Int? = null, val readFailed: Boolean = false, val now: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault())

@HiltViewModel
class BillsViewModel @Inject constructor(private val repository: BillsRepository, val media: MediaRepository,
    val processor: ImageProcessor, private val preferences: PreferencesRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(BillsState())
    val state = mutable.asStateFlow()
    val draft = saved.getStateFlow<BillDraft?>("billDraft", null)
    val accountDraft = saved.getStateFlow<AccountDraft?>("accountDraft", null)
    val detailId = saved.getStateFlow<String?>("billDetail", null)
    val accountsOpen = saved.getStateFlow("accountsOpen", false)
    val filter = saved.getStateFlow("billFilter", BillFilter())
    private var observation: Job? = null
    init { retry() }
    fun retry() {
        observation?.cancel(); mutable.update { it.copy(loading = true, readFailed = false, error = null) }
        observation = viewModelScope.launch {
            try { combine(repository.bills, repository.accounts, preferences.preferences) { bills, accounts, prefs -> Triple(bills, accounts, prefs) }
                .collect { (bills, accounts, prefs) -> mutable.update { it.copy(bills = bills, accounts = accounts, preferences = prefs, loading = false) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, readFailed = true, error = R.string.failed) } }
        }
    }
    fun refreshTime() { mutable.update { it.copy(now = System.currentTimeMillis(), zone = ZoneId.systemDefault()) } }
    fun nextTick(): Long {
        val now = mutable.value.now
        val midnight = Instant.ofEpochMilli(now).atZone(mutable.value.zone).toLocalDate().plusDays(1).atStartOfDay(mutable.value.zone).toInstant().toEpochMilli()
        val expiry = mutable.value.bills.filter { it.isGroupBuy && it.settlement == Settlement.ACTIVE }.mapNotNull { it.validUntil }
            .filter { it >= now && it < Long.MAX_VALUE }.minOrNull()?.plus(1) ?: midnight
        return (minOf(midnight, expiry) - now).coerceIn(1, 60_000)
    }
    fun filter(value: BillFilter) { saved["billFilter"] = value }
    fun show(id: String) { saved["billDetail"] = id }
    fun closeDetail() { saved["billDetail"] = null }
    fun openAccounts() { saved["accountsOpen"] = true }
    fun closeAccounts() { saved["accountsOpen"] = false }
    fun edit(bill: BillRecord? = null, duplicate: Boolean = false) {
        saved["billDraft"] = bill?.let { BillDraft.from(it, duplicate) } ?: BillDraft()
    }
    fun update(value: BillDraft) { if (!mutable.value.busy) saved["billDraft"] = value }
    fun cancelDraft() { if (!mutable.value.busy) saved["billDraft"] = null }
    fun save() = action {
        val value = requireNotNull(draft.value)
        val id = repository.save(value)
        saved["billDraft"] = null; saved["billDetail"] = id
        value.imageFile?.let { java.io.File(it).delete() }
    }
    fun delete(bill: BillRecord) = action { repository.delete(bill.id, bill.revision); saved["billDetail"] = null }
    fun redeem(bill: BillRecord) = action { repository.redeem(bill.id, bill.revision) }
    fun refund(bill: BillRecord, reason: String) = action { repository.refund(bill.id, bill.revision, reason) }
    fun editAccount(account: StoredValueAccount? = null) {
        saved["accountDraft"] = account?.let { AccountDraft(it.id, it.revision, it.name, it.kind, BillMoney.input(it.balance), it.note.orEmpty()) } ?: AccountDraft()
    }
    fun updateAccount(value: AccountDraft) { if (!mutable.value.busy) saved["accountDraft"] = value }
    fun cancelAccount() { if (!mutable.value.busy) saved["accountDraft"] = null }
    fun saveAccount(defaultName: String) = action {
        val value = requireNotNull(accountDraft.value)
        val id = repository.saveAccount(value.id, value.revision, value.name.ifBlank { defaultName }, value.kind,
            if (value.balance.isBlank()) 0 else requireNotNull(BillMoney.cents(value.balance)), value.note)
        saved["accountDraft"] = null
        draft.value?.let { saved["billDraft"] = it.copy(accountId = id) }
    }
    fun deleteAccount(account: StoredValueAccount) = action { repository.deleteAccount(account.id, account.revision) }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: StaleBillException) { mutable.update { it.copy(error = R.string.stale) } }
            catch (_: AccountInUseException) { mutable.update { it.copy(error = R.string.account_in_use) } }
            catch (_: ArithmeticException) { mutable.update { it.copy(error = R.string.overflow) } }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.failed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
