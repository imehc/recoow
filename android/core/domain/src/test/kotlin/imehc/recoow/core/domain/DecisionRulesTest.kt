package imehc.recoow.core.domain

import imehc.recoow.core.domain.decision.*
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class DecisionRulesTest {
    private fun option(id: String, weight: Long, enabled: Boolean = true) = DecisionOption(id, "", "collection", id, null, null, weight, enabled, null, false)
    @Test fun weightedBoundariesExcludeDisabledAndClampImportedZero() {
        val options = listOf(option("a", 0), option("disabled", 100, false), option("b", 3))
        val chosen = (0..3).map { ticket -> weightedChoice(options) { assertEquals(BigInteger.valueOf(4), it); BigInteger.valueOf(ticket.toLong()) }.id }
        assertEquals(listOf("a", "b", "b", "b"), chosen)
        assertThrows(IllegalArgumentException::class.java) { weightedChoice(options) { it } }
        assertThrows(IllegalArgumentException::class.java) { weightedChoice(emptyList()) { BigInteger.ZERO } }
    }
    @Test fun importedLargeWeightsDoNotOverflowOrChangeRelativeProbability() {
        val options = listOf(option("a", Long.MAX_VALUE), option("b", Long.MAX_VALUE))
        assertEquals("a", weightedChoice(options) { BigInteger.valueOf(Long.MAX_VALUE - 1) }.id)
        assertEquals("b", weightedChoice(options) { assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, it); BigInteger.valueOf(Long.MAX_VALUE) }.id)
    }
    @Test fun everyCandidateIsReachableWithinTheFixedRenderingBudget() {
        for (count in listOf(1, 2, 8, 9, 301)) for (index in 0 until count) {
            val placements = Turntable.placements(count, index.toDouble())
            assertTrue(placements.size <= 8); assertEquals(placements.size, placements.map { it.slot }.toSet().size)
            assertTrue(placements.any { it.index == index }); assertEquals(index, Turntable.focused(index.toDouble(), count))
            val target = Turntable.draw(index, count, 5.0)
            assertEquals(index, Turntable.focused(target, count))
        }
        assertTrue(Turntable.placements(0, 0.0).isEmpty())
        assertEquals(1, Turntable.focused(.5, 8)); assertEquals(7, Turntable.focused(-.5, 8))
        assertEquals("hot pot", normalizedDecisionTitle("  ＨＯＴ\u3000ＰＯＴ  "))
        assertEquals("干锅虾", normalizedDecisionTitle("\n干锅虾\t"))
    }
}
