package imehc.recoow.core.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class GlbSceneTest {
    @get:Rule val compose = createComposeRule()

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
