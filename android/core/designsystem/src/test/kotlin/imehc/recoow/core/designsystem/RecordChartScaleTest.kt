package imehc.recoow.core.designsystem

import org.junit.Assert.*
import org.junit.Test

class RecordChartScaleTest {
    @Test fun countTicksAreWholeNumbersAndIncludeTheMaximum() {
        listOf(0.0, 1.0, 3.0, 5.0, 27.0, 199.0, 100_000.0).forEach { maximum ->
            val step = recordChartStep(maximum, true)
            assertEquals(step.toLong().toDouble(), step, 0.0)
            assertTrue(step >= 1)
            assertTrue(step * 4 >= maximum)
        }
    }
    @Test fun moneyTicksPreserveSmallAmountsAndLargeTotals() {
        listOf(.01, .25, 2.73, 58.0, 1_250.0, 9.2e20).forEach { maximum ->
            val step = recordChartStep(maximum, false)
            assertTrue(step.isFinite() && step > 0)
            assertTrue(step * 4 >= maximum)
        }
        assertEquals(.0025, recordChartStep(.01, false), .000001)
    }
    @Test fun emptyOrInvalidAmountsHaveAUsableAxis() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals(1.0, recordChartStep(it, false), 0.0)
        }
    }
}
