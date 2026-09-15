package com.holopengin.instantjpdict

import kotlinx.coroutines.Job

/**
 * Close an [OcrEngine] without tearing its ncnn nets down under a live OCR pass.
 *
 * `OcrEngine.close()` calls `Net::clear()` + `delete` in JNI, and a pass's
 * `detect`/`recognizeStreaming` is non-suspending native work that no coroutine
 * cancellation can interrupt: cancelling the job stops the coroutine, not the
 * convolution it is inside. Closing there tears down a Net under a running
 * convolution, which faults inside ncnn ("pool allocator destroyed too early";
 * SIGSEGV at 0x0 on an OpenMP worker, seen twice on the device when a
 * re-creation landed mid-pass, #57).
 *
 * [pass] is the `Job` returned by `OcrOverlayView.startOcr()` (null when the host
 * started none). With a pass live the close is deferred onto that pass's own
 * completion, which cannot fire until its native work has returned; with no pass
 * there is nothing to wait for and the close is immediate.
 *
 * Both engine owners — [OcrAccessibilityService] and [ShareImageActivity] — run
 * through here, so the two cannot drift on the discipline again. The engine
 * reference is captured by the caller's lambda, so a host that is mid-teardown
 * for other reasons still closes through the same rule.
 */
internal fun closeEngineBehindPass(pass: Job?, close: () -> Unit) {
    if (pass == null) close() else pass.invokeOnCompletion { close() }
}
