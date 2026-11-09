package imehc.recoow.core.domain

import imehc.recoow.core.domain.backup.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BackupAccountRulesTest {
    private val backup = BackupPreview(1,37,"test","1",Instant.EPOCH,"phone","{}",emptyMap(),0,
        sourceAccountScope = "a".repeat(64) + ":account-a")
    @Test fun accountMismatchCannotBeBypassedByLegacyConfirmation() {
        BackupAccountRules.validate(backup,"a".repeat(64) + ":account-a",false)
        assertThrows(BackupAccountMismatch::class.java) { BackupAccountRules.validate(backup,"a".repeat(64) + ":account-b",true) }
        val legacy = backup.copy(sourceAccountScope = null, hasAccountProvenance = false)
        assertThrows(BackupAccountUnverified::class.java) { BackupAccountRules.validate(legacy,"a".repeat(64) + ":account-a",false) }
        BackupAccountRules.validate(legacy,"a".repeat(64) + ":account-a",true)
        BackupAccountRules.validate(legacy,null,false)
        assertThrows(InvalidBackupException::class.java) { BackupAccountRules.validate(backup.copy(sourceAccountScope = "malformed"),null,true) }
        assertEquals(BackupAccountStatus.UNBOUND, BackupAccountRules.status(backup.copy(sourceAccountScope = null),null))
    }
}
