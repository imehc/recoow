package imehc.recoow.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import imehc.recoow.core.domain.navigation.ToolRoute

class ToolLinkViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val pendingRoute = savedState.getStateFlow<String?>(KEY, null)

    fun receive(scheme: String?, host: String?, segments: List<String>) {
        ToolRoute.fromDeepLink(scheme, host, segments)?.let {
            val records = setOf(ToolRoute.BILLS, ToolRoute.REMINDERS, ToolRoute.DIARY, ToolRoute.FOOD, ToolRoute.ITEMS, ToolRoute.ANNIVERSARIES, ToolRoute.DECISION)
            savedState[KEY] = it.id + if (it in records && segments.size == 2 && segments[1].isNotBlank())
                "?recordId=" + android.net.Uri.encode(segments[1]) + "&delivery=" + java.util.UUID.randomUUID() else ""
        }
    }

    fun consumed(route: String) {
        if (pendingRoute.value == route) savedState[KEY] = null
    }

    private companion object { const val KEY = "pending_tool_link" }
}
