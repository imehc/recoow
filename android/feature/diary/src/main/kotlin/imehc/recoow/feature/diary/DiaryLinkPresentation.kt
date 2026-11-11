package imehc.recoow.feature.diary

import android.content.res.Resources
import imehc.recoow.core.domain.diary.DiaryLink
import imehc.recoow.core.domain.bills.BillType
import org.json.JSONObject
import imehc.recoow.core.designsystem.recordBillSourceLabel
import imehc.recoow.core.designsystem.recordBillTypeLabel

internal fun diaryLinkSubtitle(link: DiaryLink, resources: Resources): String? {
    val subtitle = link.subtitle ?: return null
    if (link.sourceType != "bill") return subtitle
    return subtitle.split(" · ").mapIndexed { index, part ->
        if (index == 0) part else recordBillSourceLabel(part, resources)
    }.joinToString(" · ")
}

internal data class DiaryBillDisplay(val amount: String, val type: BillType?, val subtitle: String?)

internal fun diaryBillDisplay(link: DiaryLink, resources: Resources): DiaryBillDisplay? {
    if (link.sourceType != "bill") return null
    val snapshot = link.snapshot?.let { runCatching { JSONObject(it) }.getOrNull() }
    val type = snapshot?.optString("transactionType")?.let { wire -> BillType.entries.firstOrNull { it.wire == wire } }
    val parts = diaryLinkSubtitle(link, resources)?.split(" · ").orEmpty()
    val amount = snapshot?.optString("displayAmount")?.takeIf { it.isNotBlank() && it != "null" }
        ?: parts.firstOrNull()?.takeIf { it.toBigDecimalOrNull() != null } ?: return null
    val subtitle = if (parts.firstOrNull()?.toBigDecimalOrNull() != null) parts.drop(1).joinToString(" · ")
        else parts.joinToString(" · ")
    return DiaryBillDisplay(amount, type, subtitle.takeIf { it.isNotBlank() })
}

internal fun diaryBillTypeLabel(type: BillType?, resources: Resources): String = recordBillTypeLabel(type, resources)
