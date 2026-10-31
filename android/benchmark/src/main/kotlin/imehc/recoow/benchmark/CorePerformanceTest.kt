package imehc.recoow.benchmark

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

private const val PACKAGE = "imehc.recoow.benchmark"

private fun prepareFixture() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    check(device.executeShellCommand("pm clear $PACKAGE").trim() == "Success")
    device.executeShellCommand("am start -W -n $PACKAGE/imehc.recoow.performance.BenchmarkFixtureActivity")
    device.wait(Until.hasObject(By.textStartsWith("Benchmark fixture ")), 90_000)
    check(device.hasObject(By.text("Benchmark fixture ready"))) {
        "Fixture failed: ${device.findObject(By.textStartsWith("Benchmark fixture failed"))?.text}"
    }
    device.executeShellCommand("am force-stop $PACKAGE")
}

private fun MacrobenchmarkScope.waitTag(tag: String) {
    check(device.wait(Until.hasObject(By.res(tag)), 15_000)) { "Missing UI: $tag" }
}

private fun MacrobenchmarkScope.openTool(tool: String, id: String? = null, tag: String? = "tool_$tool") {
    startActivityAndWait(Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/$tool" + (id?.let { "/$it" } ?: ""))).setPackage(PACKAGE))
    tag?.let { waitTag(it) }
}

private fun MacrobenchmarkScope.find(selector: BySelector): UiObject2 {
    repeat(14) {
        device.wait(Until.findObject(selector), 500)?.let { return it }
        val list = checkNotNull(device.wait(Until.findObject(By.scrollable(true)), 10_000)) { "No list while looking for $selector" }
        if (!list.scroll(Direction.DOWN, 0.65f)) {
            device.wait(Until.findObject(selector), 1_500)?.let { return it }
            error("Reached list end while looking for $selector")
        }
    }
    error("Missing UI: $selector")
}

private fun MacrobenchmarkScope.tap(tag: String) { find(By.res(tag)).click(); device.waitForIdle() }
private fun MacrobenchmarkScope.tapText(text: String) { find(By.text(text)).click(); device.waitForIdle() }

private fun MacrobenchmarkScope.scrollList(sweeps: Int = 1) {
    val list = checkNotNull(device.wait(Until.findObject(By.scrollable(true)), 10_000)) { "Missing scrollable list" }
    list.setGestureMargin(device.displayWidth / 6)
    repeat(sweeps) { list.fling(Direction.DOWN); device.waitForIdle() }
    repeat(sweeps) { list.fling(Direction.UP); device.waitForIdle() }
}

private fun MacrobenchmarkScope.home() {
    killProcess(); pressHome(); startActivityAndWait(); waitTag("tab_home")
}

private fun MacrobenchmarkScope.allJourneys() {
    home()
    tap("tab_statistics"); tap("statistics_history"); waitTag("history_page"); scrollList()
    device.pressBack(); tap("tab_settings")
    for (preference in listOf("appearance", "language")) {
        tap(preference)
        // Dialogs use a separate semantics root; activity test resource IDs do not propagate.
        val cancel = By.text("Cancel")
        check(device.wait(Until.hasObject(cancel), 10_000)) { "Preference dialog did not open: $preference" }
        device.pressBack()
        check(device.wait(Until.gone(cancel), 10_000)) { "Preference dialog did not close" }
    }
    tap("tab_home")
    openTool("bills"); scrollList()
    openTool("bills", "perf-bill-0", null); find(By.text("Performance bill 0")); tapText("Edit"); waitTag("bill_form")
    device.pressBack(); device.pressBack(); device.pressBack()
    openTool("reminders", "perf-reminder-0", "reminder_detail"); device.pressBack(); device.pressBack()
    openTool("diary"); scrollList(); openTool("diary", "perf-diary-0", "diary_detail"); find(By.text("Performance diary 0")); device.pressBack(); device.pressBack()
    openTool("foodJournal"); tapText("Performance food day"); waitTag("food_day_detail"); tapText("Performance meal 0"); waitTag("food_detail")
    device.pressBack(); device.pressBack(); device.pressBack()
    openTool("itemLocator"); scrollList(); openTool("itemLocator", "perf-item-0", "item_detail"); device.pressBack(); device.pressBack()
    openTool("anniversaries", "perf-anniversary-0", "anniversary_detail"); device.pressBack(); device.pressBack()
    decisionJourney(); device.pressBack(); device.pressBack()
    openTool("locationTracker", "perf-track", "track_detail")
    find(By.descStartsWith("Showing a Map created with MapLibre"))
    tapText("Play")
    check(device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("Point ([2-9]|[1-9][0-9]+) / 600"))), 10_000)) { "Playback did not advance" }
    tapText("Pause"); scrollList()
    device.pressBack(); device.pressBack()
}

private fun MacrobenchmarkScope.decisionJourney() {
    openTool("decisionMaker"); tapText("Performance collection"); waitTag("decision_stage"); waitTag("decision_scene_ready")
    tapText("Next"); tapText("Previous"); tapText("Choose")
    check(device.wait(Until.hasObject(By.text("View saved result")), 15_000)) { "Choice result was not saved" }
    tapText("View saved result"); waitTag("decision_detail"); device.pressBack()
}

/** Fixture setup is outside collection; only measured application journeys are retained. */
class CoreBaselineProfile {
    companion object { @JvmStatic @BeforeClass fun setup() = prepareFixture() }
    @get:Rule val rule = BaselineProfileRule()
    private val appCode: (String) -> Boolean = { it.contains("Limehc/recoow/") && !it.contains("Limehc/recoow/performance/") }
    @Test fun startup() = rule.collect(PACKAGE, includeInStartupProfile = true, maxIterations = 5, filterPredicate = appCode) {
        pressHome(); startActivityAndWait()
    }
    @Test fun coreJourneys() = rule.collect(PACKAGE, maxIterations = 5, filterPredicate = appCode) { allJourneys() }
}

/** Emulator runs validate the harness. Physical-device results establish regression budgets. */
class CoreStartupBenchmark {
    companion object { @JvmStatic @BeforeClass fun setup() = prepareFixture() }
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun coldStartWithoutProfile() = measure(CompilationMode.None())
    @Test fun coldStartWithProfile() = measure(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))
    private fun measure(compilation: CompilationMode) = rule.measureRepeated(PACKAGE,
        metrics = listOf(StartupTimingMetric()), compilationMode = compilation, startupMode = StartupMode.COLD,
        iterations = 5, setupBlock = { pressHome() }) { startActivityAndWait() }
}

@OptIn(ExperimentalMetricApi::class)
class CoreJourneyBenchmark {
    companion object { @JvmStatic @BeforeClass fun setup() = prepareFixture() }
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun populatedBillList() = rule.measureRepeated(PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        iterations = 5, setupBlock = { home(); openTool("bills"); waitTag("bill_search") }) { scrollList(sweeps = 3) }
    @Test fun populatedHistory() = rule.measureRepeated(PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        iterations = 5, setupBlock = { home(); tap("tab_statistics"); tap("statistics_history"); waitTag("history_page") }) { scrollList(sweeps = 3) }
    @Test fun decisionScene() = rule.measureRepeated(PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)) +
            if (Build.VERSION.SDK_INT >= 29) listOf(TraceSectionMetric("recoow.decision.firstFrame", mode = TraceSectionMetric.Mode.First)) else emptyList(),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        iterations = 5, setupBlock = { home() }) { decisionJourney() }
}
