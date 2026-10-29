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
