package com.holopengin.instantjpdict

/**
 * #57 rapid-press coalescing: the quarter-turn bookkeeping behind the share
 * activity's rotate buttons, kept free of Android types so the JVM tests can
 * pin it — the same split [ImageRotation] uses for the geometry.
 *
 * Why it exists: every press used to start its own full detect/recognise pass
 * — a fresh view-sized composite (≈10 MB at 1080×2400) plus the detector's own
 * buffers — while the previous pass was still running. A burst of presses
 * therefore stacked allocations faster than they were released and the process
 * died of `OutOfMemoryError`; recycling the composite a live pass was reading
 * raised "Bitmap is recycled" instead. A pass cannot be interrupted (the
 * detect call is native and cancellation is cooperative), so this queue does
 * not pretend to stop it: a press that lands while a pass is in flight only
 * accumulates a requested turn, and when that pass finishes exactly ONE more
 * pass runs for whatever accumulated. N presses cost one pass, not N.
 *
 * Turns are always NET off the image on screen, and the composite is always
 * rebuilt from the unrotated base ([ImageRotation.fitRotated]), so a burst of
 * four presses costs no pass at all and a burst of three costs a single
 * three-quarter pass — the same orientation, and the same pixels, a single
 * coalesced rotation would have produced, with no compounded resampling.
 */
object RotationQueue {

    /**
     * What the surface is showing (or building) and what is queued behind it.
     * Immutable: the transitions below return the next state, so they are the
     * whole state machine and a test can walk it a press at a time.
     */
    data class State(
        /** Quarter turns (0..3) the pass in flight is for — what is on screen. */
        val turns: Int = 0,
        /** Net quarter turns (0..3) requested while it ran, not applied yet. */
        val queued: Int = 0,
        /** True while a pass is in flight: a press then queues instead of starting one. */
        val running: Boolean = false,
    ) {
        companion object {
            /** Nothing on screen and no pass in flight. */
            val IDLE = State()
        }
    }

    /**
     * A press. With no pass in flight it IS the next pass, so the orientation
     * changes straight away; with one in flight it only accumulates, wrapped —
     * four presses while a pass runs leave the orientation where it was and
     * cost no further pass.
     */
    fun press(state: State, clockwise: Boolean): State =
        if (state.running) state.copy(queued = ImageRotation.turn(state.queued, clockwise))
        else state.copy(turns = ImageRotation.turn(state.turns, clockwise), running = true)

    /**
     * A pass is now in flight that this queue did not start — the host's first
     * detect/recognise run. Presses during it must queue like any other pass.
     */
    fun passStarted(state: State): State = state.copy(running = true)

    /**
     * The pass in flight finished. Queued turns become the next pass — one, for
     * however many presses accumulated; with nothing queued the queue idles.
     */
    fun passFinished(state: State): State =
        if (state.queued == 0) state.copy(running = false)
        else State(turns = (state.turns + state.queued) % 4, queued = 0, running = true)

    /**
     * The pass in flight is being abandoned (the host is going away, or the
     * rotated composite could not be built). Park the queue at what is
     * displayed rather than leaving a pass marked in flight that nothing will
     * ever finish — a later press still starts a pass.
     */
    fun passAbandoned(state: State): State = state.copy(queued = 0, running = false)
}
