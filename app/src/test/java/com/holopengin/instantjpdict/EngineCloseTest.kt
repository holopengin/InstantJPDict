package com.holopengin.instantjpdict

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * A1/#86: the pass-aware engine-close discipline shared by the accessibility
 * service and the share activity (see [closeEngineBehindPass]).
 *
 * A plain [Job] stands in for `OcrOverlayView.startOcr()`'s run: it is the same
 * completion signal the native work gates, so what is pinned here is *when*
 * the close runs relative to the pass, not any coroutine machinery.
 */
class EngineCloseTest {
    @Test
    fun noPass_closesImmediately() {
        var closed = false
        closeEngineBehindPass(null) { closed = true }
        assertTrue("with no pass in flight there is nothing to wait for", closed)
    }

    @Test
    fun livePass_defersCloseUntilThePassCompletes() {
        val pass = Job()
        var closed = false
        closeEngineBehindPass(pass) { closed = true }
        assertFalse("a pass still inside the nets must not have them torn down", closed)
        pass.complete()
        assertTrue("the close lands once the pass has actually returned", closed)
    }

    @Test
    fun cancelledButRunningPass_defersCloseUntilTheBodyReturns() {
        // `scope.cancel()` on teardown stops the coroutine, not the native detect
        // it is blocked in: the job stays in "cancelling" until the body unwinds,
        // and the close must wait for that. The sleeping body stands in for the
        // non-suspending native work. A job that has already reached a final
        // state closes at once — that is the state the next onDestroy sees after
        // a pass returned normally.
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val started = CountDownLatch(1)
            val pass = scope.launch {
                started.countDown()
                Thread.sleep(100)
            }
            started.await()
            pass.cancel()
            var closed = false
            closeEngineBehindPass(pass) { closed = true }
            assertFalse("cancellation is not completion", closed)
            runBlocking { pass.join() }
            assertTrue("the close lands once the body has unwound", closed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun alreadyCompletedPass_closesImmediately() {
        val pass = Job()
        pass.complete()
        var closed = false
        closeEngineBehindPass(pass) { closed = true }
        assertTrue("a finished pass cannot be reading the nets", closed)
    }
}
