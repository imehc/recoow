package imehc.recoow.core.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlbSceneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun decisionShowsFirstModelBeforeRemainingModelsAndFinishesWithoutHiding() {
        val defer = mutableStateOf(true)
        val loaded = AtomicInteger()
        val progress = AtomicReference(0f)
        val progressAtReady = AtomicReference(1f)
        val failure = AtomicReference<Throwable?>()
        val keys = listOf("burger", "noodles", "salad", "sushi", "sandwich", "hotpot", "skewers", "malatang", "barbecue", "luosifen", "drypotshrimp", "default")
        val cursor = mutableStateOf(0.0)
        compose.setContent {
            DecisionScene(keys, 0.0, false, Modifier.fillMaxSize(), modelKeys = keys,
                cursorProvider = { cursor.value }, deferModelLoading = { defer.value },
                onLoaded = { progressAtReady.set(progress.get()); loaded.incrementAndGet() },
                onProgress = { progress.set(it) }, onFailure = { failure.set(it) })
        }
        compose.waitUntil(30_000) { loaded.get() == 1 || failure.get() != null }
        assertNull(failure.get())
        assertTrue("The first model must be revealed before the complete catalog", progressAtReady.get() > 0f && progressAtReady.get() < 1f)
        compose.runOnIdle { cursor.value = 9.0; defer.value = false }
        compose.waitUntil(30_000) { progress.get() == 1f || failure.get() != null }
        assertNull(failure.get())
        assertTrue("Background models must not restart the reveal", loaded.get() == 1)
    }

    @Test fun decisionFirstFrameRevealsAndCanBeReentered() {
        val visible = mutableStateOf(true)
        val loaded = AtomicInteger()
        val frames = AtomicInteger()
        val framesAtReady = AtomicInteger(-1)
        val failure = AtomicReference<Throwable?>()
        compose.setContent {
            if (visible.value) DecisionScene(listOf("汉堡", "面条", "沙拉"), 0.0, false, Modifier.fillMaxSize(),
                onLoaded = { framesAtReady.set(frames.get()); loaded.incrementAndGet() },
                onFrame = { frames.incrementAndGet() }, onFailure = { failure.set(it) })
        }
        compose.waitUntil(30_000) { loaded.get() == 1 || failure.get() != null }
        assertNull(failure.get())
        assertTrue("Readiness must follow a submitted frame", framesAtReady.get() > 0)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        frames.set(0)
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(30_000) { loaded.get() == 2 || failure.get() != null }
        assertNull(failure.get())
        assertTrue("The recreated surface must render before revealing", framesAtReady.get() > 0)
    }

    @Test fun decisionTurntableBrowsesLargePoolChangesThemeAndRecreates() {
        val visible = mutableStateOf(true); val cursor = mutableStateOf(0.0); val dark = mutableStateOf(false)
        val loaded = AtomicInteger(); val frames = AtomicInteger(); val failure = AtomicReference<Throwable?>()
        val titles = (0 until 301).map { listOf("汉堡", "hot pot", "Missing alias")[it % 3] }
        compose.setContent {
            if (visible.value) DecisionScene(titles, cursor.value, dark.value, Modifier.fillMaxSize(),
                onLoaded = { loaded.incrementAndGet() }, onFrame = { frames.incrementAndGet() }, onFailure = { failure.set(it) })
        }
        compose.waitUntil(30_000) { loaded.get() >= 1 && frames.get() >= 5 || failure.get() != null }; assertNull(failure.get())
        compose.runOnIdle { cursor.value = 300.0; dark.value = true }
        compose.waitUntil(30_000) { loaded.get() >= 2 || failure.get() != null }; assertNull(failure.get())
        compose.runOnIdle { visible.value = false }; compose.waitForIdle(); val before = frames.get()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(30_000) { loaded.get() >= 3 && frames.get() > before + 5 || failure.get() != null }; assertNull(failure.get())
    }

    @Test fun sharedSceneLoadsRendersAndCanBeReleasedThenRecreated() {
        val visible = mutableStateOf(true)
        val loaded = AtomicInteger()
        val frames = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        compose.setContent {
            if (visible.value) GlbScene("decision-scene.glb", Modifier.fillMaxSize(),
                onLoaded = { loaded.incrementAndGet() }, onFrame = { frames.incrementAndGet() }, onFailure = { failure.set(it) })
        }
        compose.waitUntil(30_000) { frames.get() >= 5 || failure.get() != null }
        assertNull(failure.get())
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        frames.set(0)
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(30_000) { loaded.get() == 2 && frames.get() >= 5 || failure.get() != null }
        assertNull(failure.get())
    }
}
