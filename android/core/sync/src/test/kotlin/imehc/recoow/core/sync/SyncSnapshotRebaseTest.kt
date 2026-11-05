package imehc.recoow.core.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncSnapshotRebaseTest {
    private fun fields(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun numericNotationAndLocalStorageDoNotCauseConflicts() {
        assertTrue(SyncSnapshotRebase.equal(fields("""{"value":0,"nested":[1e-6]}"""), fields("""{"value":0.0,"nested":[0.000001]}""")))
        assertFalse(SyncSnapshotRebase.equal(JsonPrimitive(9007199254740993L), JsonPrimitive(9007199254740992L)))
        assertFalse(SyncSnapshotRebase.equal(JsonNull, null))
        val base = fields("""{"storage_key":"images/a.jpg","checksum":"same","title":"old"}""")
        val local = fields("""{"storage_key":"sync/same","checksum":"same","title":"new"}""")
        val remote = fields("""{"storage_key":"sync/other-path","checksum":"same","title":"old"}""")
        assertFalse(SyncSnapshotRebase.conflicts("media_assets", base, local, remote))
        val rebased = SyncSnapshotRebase.snapshot("media_assets", base, local, remote)
        assertEquals(remote["storage_key"], rebased["storage_key"])
        assertEquals(local["title"], rebased["title"])
        val changed = fields("""{"storage_key":"sync/changed","checksum":"changed","title":"old"}""")
        assertTrue(SyncSnapshotRebase.conflicts("media_assets", base, changed, fields("""{"checksum":"another"}""")))
    }

    @Test fun independentQueuedEditKeepsCloudValues() {
        val base = fields("""{"title":"device","note":"old","updated_at":10,"device_id":"ios"}""")
        val next = fields("""{"title":"device","note":"later","updated_at":11,"device_id":"ios"}""")
        val remote = fields("""{"title":"device","note":"old","mood":"cloud","updated_at":12,"device_id":"android"}""")
        assertFalse(SyncSnapshotRebase.conflicts("diary_entries", base, next, remote))
        val result = SyncSnapshotRebase.snapshot("diary_entries", base, next, remote)
        assertEquals(JsonPrimitive("cloud"), result["mood"])
        assertEquals(JsonPrimitive("later"), result["note"])
        assertEquals(JsonPrimitive(11), result["updated_at"])
    }

    @Test fun overlappingInFlightEditRequiresConfirmation() {
        val base = fields("""{"note":"old"}""")
        val local = fields("""{"note":"device"}""")
        val remote = fields("""{"note":"cloud"}""")
        assertTrue(SyncSnapshotRebase.conflicts("diary_entries", base, local, remote))
        assertFalse(SyncSnapshotRebase.conflicts("diary_entries", base, remote, remote))
    }

    @Test fun moneyAndPaymentAreOneValue() {
        val base = fields("""{"final_amount_cents":100,"payment_method":"cash"}""")
        val local = fields("""{"final_amount_cents":100,"payment_method":"alipay"}""")
        val remote = fields("""{"final_amount_cents":120,"payment_method":"cash"}""")
        assertTrue(SyncSnapshotRebase.conflicts("bills", base, local, remote))
    }

    @Test fun nullUnknownFieldsAndInt64ArePreserved() {
        val base = fields("""{"note":"old","future":9007199254740993}""")
        val local = fields("""{"note":null}""")
        val remote = fields("""{"note":"old","future":9007199254740993}""")
        val result = SyncSnapshotRebase.snapshot("diary_entries", base, local, remote)
        assertEquals(9007199254740993L, result.getValue("future").jsonPrimitive.long)
        assertEquals(JsonNull, result["note"])
        assertNotEquals(JsonPrimitive(9007199254740993L), JsonPrimitive(9007199254740992L))
    }

    @Test fun deletionIsNeverRebasedAcrossAnEdit() {
        val base = fields("""{"note":"old","deleted_at":null}""")
        val local = fields("""{"note":"device","deleted_at":null}""")
        val deleted = fields("""{"note":"old","deleted_at":10}""")
        assertTrue(SyncSnapshotRebase.conflicts("diary_entries", base, local, deleted))
    }
}
