package imehc.recoow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.sync.AccountSecurityRepository
import imehc.recoow.core.domain.sync.SyncRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class AccountSecurityViewModel @Inject constructor(
    private val accounts: AccountSecurityRepository, private val sync: SyncRepository,
) : ViewModel() {
    val state = accounts.accountState
    val syncState = sync.state
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            sync.state.map { it.connection }.distinctUntilChanged().collect { accounts.refreshAccount() }
        }
    }

    fun synchronize() = action { sync.sync() }
    fun setSyncEnabled(enabled: Boolean) = action { sync.setEnabled(enabled); if (enabled) sync.sync() }
    fun disconnect() = action { sync.disconnect() }
    fun confirmReset() = action { sync.confirmCloudReset(); if (sync.state.value.problem == null) sync.sync() }
    fun refresh() = action { accounts.refreshAccount() }
    fun set(password: String) = action { accounts.setAccountPassword(password) }
    fun change(current: String, next: String) = action { accounts.changeAccountPassword(current, next) }
    fun reset(endpoint: String, recovery: String, next: String) = action { accounts.resetAccountPassword(endpoint, recovery, next) }
    private fun action(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { block() }
    }
}
