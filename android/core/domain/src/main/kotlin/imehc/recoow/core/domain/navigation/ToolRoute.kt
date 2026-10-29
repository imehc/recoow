package imehc.recoow.core.domain.navigation

/** 跨平台链接的工具标识保持不变；页面是否可用由应用路由层决定。 */
enum class ToolRoute(val id: String) {
    BILLS("bills"), REMINDERS("reminders"), DIARY("diary"), FOOD("foodJournal"),
    DECISION("decisionMaker"), ITEMS("itemLocator"), ANNIVERSARIES("anniversaries"),
    LOCATION("locationTracker");

    companion object {
        fun fromDeepLink(scheme: String?, host: String?, segments: List<String>): ToolRoute? {
            if (!scheme.equals("recoow", ignoreCase = true) || !host.equals("tool", ignoreCase = true)) return null
            return entries.firstOrNull { it.id == segments.firstOrNull() }
        }
    }
}
