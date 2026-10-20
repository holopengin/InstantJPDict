package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #89: a text-selection lookup must show its results and stay up, like a share.
 *
 * The screen used to call `finish()` on the same main-thread turn that built the
 * result cards. `finish()` tears the window down before a frame is drawn, so the
 * user saw the lookup run and the screen close with nothing in between. This
 * test drives the real `ACTION_PROCESS_TEXT` launch and waits for the results
 * column to fill. It then checks the activity is still resumed, which is the
 * user-visible half the old code got wrong.
 *
 * Device-only: the lookup needs the packaged assets and the dictionary
 * database, so this runs under `connectedBenchmarkAndroidTest`, not the JVM
 * suite.
 */
@RunWith(AndroidJUnit4::class)
class ManualLookupProcessTextTest {

    @Test
    fun aSelectionLookupShowsResultsAndStaysOnScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ManualLookupActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            putExtra(Intent.EXTRA_PROCESS_TEXT, "食べる")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            // Started from the instrumentation context, like the platform's
            // selection toolbar starting another app's activity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<ManualLookupActivity>(intent).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + LOOKUP_TIMEOUT_MS
            var results = 0
            while (results == 0 && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    results = activity.findViewById<ViewGroup>(R.id.manual_lookup_results)
                        ?.childCount ?: 0
                }
                if (results == 0) Thread.sleep(POLL_MS)
            }

            assertTrue(
                "the selection lookup left the results column empty for ${LOOKUP_TIMEOUT_MS}ms",
                results > 0,
            )
            assertEquals(
                "the selection lookup closed itself after showing results",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
            scenario.onActivity { activity ->
                assertFalse("the selection lookup is finishing", activity.isFinishing)
            }
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
