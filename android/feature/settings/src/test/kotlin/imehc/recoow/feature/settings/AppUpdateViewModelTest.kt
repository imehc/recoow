package imehc.recoow.feature.settings

import imehc.recoow.core.domain.update.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class FakeUpdates : AppUpdates {
        override val installed = InstalledAppVersion("0.6.0", 6)
        var available: AppRelease? = AppRelease("0.6.1", 7, "https://example.invalid/app.apk", "a".repeat(64), 100, 26, "notes")
        var checks = 0
        var problem: AppUpdateProblem? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        override suspend fun check(): AppRelease? { checks++; problem?.let { throw AppUpdateException(it) }; return available }
        override suspend fun download(release: AppRelease, progress: (Long, Long) -> Unit): PreparedAppUpdate {
            progress(50, 100)
            downloadGate?.await()
            return PreparedAppUpdate(release)
        }
        override suspend fun installationUri(update: PreparedAppUpdate): String {
            problem?.let { throw AppUpdateException(it) }
            return "content://imehc.recoow.updates/verified/app.apk"
        }
    }

    @Test fun checkOnceIsIdempotentAndNoUpdateClearsOldRelease() = runTest(dispatcher) {
        val fake = FakeUpdates()
        val model = AppUpdateViewModel(fake)
        model.checkOnce(); model.checkOnce(); advanceUntilIdle()
        model.checkOnce(); advanceUntilIdle()
        assertEquals(1, fake.checks)
        assertNotNull(model.state.value.release)
        fake.available = null
        model.check(); advanceUntilIdle()
        assertNull(model.state.value.release)
        assertTrue(model.state.value.checked)
    }

    @Test fun cancellationClearsBusyAndAllowsRetry() = runTest(dispatcher) {
        val fake = FakeUpdates()
        val model = AppUpdateViewModel(fake)
        model.check(); advanceUntilIdle()
        fake.downloadGate = CompletableDeferred()
        model.download(); runCurrent()
        assertEquals(0.5f, model.state.value.progress!!, 0.01f)
        model.cancelDownload(); advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.prepared)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.progress)
        fake.downloadGate = null
        model.download(); advanceUntilIdle()
        assertNotNull(model.state.value.prepared)
    }

    @Test fun failuresAllowRetryAndInstallationRevalidates() = runTest(dispatcher) {
        val fake = FakeUpdates()
        val model = AppUpdateViewModel(fake)
        fake.problem = AppUpdateProblem.CONNECTION
        model.check(); advanceUntilIdle()
        assertEquals(AppUpdateProblem.CONNECTION, model.state.value.problem)
        fake.problem = null
        model.check(); advanceUntilIdle()
        model.download(); advanceUntilIdle()
        model.requireInstallPermission()
        assertTrue(model.state.value.needsInstallPermission)
        fake.problem = AppUpdateProblem.INVALID_RELEASE
        var launched = false
        model.install { launched = true }; advanceUntilIdle()
        assertFalse(launched)
        assertEquals(AppUpdateProblem.INVALID_RELEASE, model.state.value.problem)
        fake.problem = null
        model.install { launched = true }; advanceUntilIdle()
        assertTrue(launched)
        assertNull(model.state.value.problem)
    }
}
