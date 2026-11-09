package imehc.recoow.core.domain.sync

import kotlinx.coroutines.flow.StateFlow

enum class AccountProblem { CONNECTION, AUTHENTICATION, PERMISSION, UNSUPPORTED, CONFLICT, RATE_LIMIT, PASSWORD, STORAGE }
enum class AccountResult { PASSWORD_SET, PASSWORD_CHANGED, PASSWORD_RESET }
data class AccountSecurityState(
    val loaded: Boolean = false, val busy: Boolean = false, val supported: Boolean = false,
    val username: String? = null, val resetUsername: String? = null,
    val problem: AccountProblem? = null, val result: AccountResult? = null,
)

interface AccountSecurityRepository {
    val accountState: StateFlow<AccountSecurityState>
    suspend fun refreshAccount()
    suspend fun setAccountPassword(password: String)
    suspend fun changeAccountPassword(current: String, next: String)
    suspend fun resetAccountPassword(endpoint: String, recoveryKey: String, next: String)
}

object AccountPasswordRules {
    fun valid(password: String): Boolean = password.length in 6..18 && charactersValid(password)
    fun charactersValid(password: String): Boolean = password.all { it in '!'..'~' }
}
