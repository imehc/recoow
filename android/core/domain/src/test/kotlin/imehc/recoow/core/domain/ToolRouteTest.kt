package imehc.recoow.core.domain

import imehc.recoow.core.domain.navigation.ToolRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRouteTest {
    @Test fun allEightIOSIdentifiersResolve() {
        val iosIdentifiers = mapOf(
            "bills" to ToolRoute.BILLS,
            "reminders" to ToolRoute.REMINDERS,
            "diary" to ToolRoute.DIARY,
            "foodJournal" to ToolRoute.FOOD,
            "decisionMaker" to ToolRoute.DECISION,
            "itemLocator" to ToolRoute.ITEMS,
            "anniversaries" to ToolRoute.ANNIVERSARIES,
            "locationTracker" to ToolRoute.LOCATION,
        )
        assertEquals(iosIdentifiers.keys, ToolRoute.entries.map { it.id }.toSet())
        iosIdentifiers.forEach { (identifier, route) ->
            assertEquals(route, ToolRoute.fromDeepLink("recoow", "tool", listOf(identifier)))
        }
    }

    @Test fun externalAndUnknownLinksAreRejected() {
        assertNull(ToolRoute.fromDeepLink("https", "tool", listOf("bills")))
        assertNull(ToolRoute.fromDeepLink("recoow", "account", listOf("bills")))
        assertNull(ToolRoute.fromDeepLink("recoow", "tool", listOf("unknown")))
        assertNull(ToolRoute.fromDeepLink("recoow", "tool", emptyList()))
    }

    @Test fun schemeAndHostMatchCaseInsensitivelyLikeIOS() {
        assertEquals(ToolRoute.BILLS, ToolRoute.fromDeepLink("RECOOW", "TOOL", listOf("bills")))
        assertNull(ToolRoute.fromDeepLink("recoow", "tool", listOf("BILLS")))
    }
}
