package imehc.recoow.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import imehc.recoow.core.domain.navigation.ToolRoute

class ToolLinkViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val pendingRoute = savedState.getStateFlow<String?>(KEY, null)

    fun receive(scheme: String?, host: String?, segments: List<String>) {
        ToolRoute.fromDeepLink(scheme, host, segments)?.let { savedState[KEY] = it.id + if (it == ToolRoute.REMINDERS && segments.size == 2) "?recordId=" + android.net.Uri.encode(segments[1]) + "&delivery=" + java.util.UUID.randomUUID() else "" }
    }

    fun consumed(route: String) {
        if (pendingRoute.value == route) savedState[KEY] = null
    }

    private companion object { const val KEY = "pending_tool_link" }
}
