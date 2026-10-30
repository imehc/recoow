package imehc.recoow.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

private const val PACKAGE = "imehc.recoow.benchmark"

private fun MacrobenchmarkScope.openTool(tool: String) {
    startActivityAndWait(Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/$tool")).setPackage(PACKAGE))
    check(device.wait(Until.hasObject(By.res("tool_$tool")), 10_000)) { "Tool did not open: $tool" }
}
private fun MacrobenchmarkScope.tap(tag: String) {
    val node = checkNotNull(device.wait(Until.findObject(By.res(tag)), 10_000)) { "Missing UI: $tag" }
    node.click(); device.waitForIdle()
}

/** Outputs are collected from the device; only reviewed profile inputs belong in app/src/main. */
class CoreBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()
    @Test fun startup() { rule.collect(PACKAGE, includeInStartupProfile = true, maxIterations = 5,
        filterPredicate = { it.contains("Limehc/recoow/") }) { pressHome(); startActivityAndWait() } }
    @Test fun coreJourneys() { rule.collect(PACKAGE, maxIterations = 5, filterPredicate = { it.contains("Limehc/recoow/") }) {
        pressHome(); startActivityAndWait()
        tap("tab_statistics"); tap("tab_settings"); tap("tab_home")
        openTool("bills"); device.pressBack()
        openTool("reminders"); tap("reminder_add"); device.pressBack(); device.pressBack()
    } }
}

/** Compare on a physical device; emulator timings are suitable only for validating the harness. */
class CoreStartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun coldStartWithoutProfile() = measure(CompilationMode.None())
    @Test fun coldStartWithProfile() = measure(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))
    private fun measure(compilation: CompilationMode) = rule.measureRepeated(PACKAGE,
        metrics = listOf(StartupTimingMetric()), compilationMode = compilation, startupMode = StartupMode.COLD,
        iterations = 5, setupBlock = { pressHome() }) { startActivityAndWait() }
}
