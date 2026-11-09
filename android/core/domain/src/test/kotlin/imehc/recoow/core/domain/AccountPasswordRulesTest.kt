package imehc.recoow.core.domain

import imehc.recoow.core.domain.sync.AccountPasswordRules
import org.junit.Assert.*
import org.junit.Test

class AccountPasswordRulesTest {
    @Test fun newPasswordsUseCommonCharactersWithinTheLengthLimit() {
        assertFalse(AccountPasswordRules.valid("a".repeat(5)))
        assertTrue(AccountPasswordRules.valid("a".repeat(6)))
        assertTrue(AccountPasswordRules.valid("a".repeat(18)))
        assertFalse(AccountPasswordRules.valid("a".repeat(19)))
        assertFalse(AccountPasswordRules.valid("密".repeat(4)))
        assertFalse(AccountPasswordRules.valid("密".repeat(86)))
        assertFalse(AccountPasswordRules.valid(" password   "))
        assertFalse(AccountPasswordRules.valid("password123\n"))
        assertTrue(AccountPasswordRules.valid("Ab1!@#$%^&*()_+-="))
    }
}
