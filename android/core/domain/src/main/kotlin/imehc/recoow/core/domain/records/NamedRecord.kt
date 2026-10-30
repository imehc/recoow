package imehc.recoow.core.domain.records

import java.io.Serializable
import java.util.UUID

/** Labels have their own lifecycle; deleting one never rewrites records that refer to it. */
data class NamedRecord(val id: String, val revision: String, val name: String, val note: String? = null)
data class NamedRecordDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null,
    val name: String = "", val note: String = "") : Serializable {
    companion object { fun from(record: NamedRecord) = NamedRecordDraft(record.id, record.revision, record.name, record.note.orEmpty()) }
}
class StaleRecordException : IllegalStateException("Record changed; reopen it before saving")
