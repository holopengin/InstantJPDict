package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

/**
 * #57 rapid-press coalescing: the state machine that stops a burst of rotate
 * presses from starting one full detect/recognise pass each. The pump that
 * walks these transitions is view/host construction and cannot be JVM-tested;
 * the transitions themselves are plain arithmetic, so they are pinned here.
 *
 * The load-bearing cases are [aBurstWhileAPassRunsCostsExactlyOneMorePass]
 * (the OOM: N presses must not cost N passes) and
 * [coalescedBurstLandsWherePressesOneAtATimeWouldHave] (coalescing must not
 * change the orientation the user asked for).
 */
class RotationQueueTest {

    @Test
    fun pressWithNothingInFlightIsItsOwnPass() {
        val next = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        assertTrue(next.running)
        assertEquals(1, next.turns)
        assertEquals(0, next.queued)
    }

    @Test
    fun pressCounterclockwiseWithNothingInFlightAlsoStartsOnePass() {
        val next = RotationQueue.press(RotationQueue.State.IDLE, clockwise = false)
        assertTrue(next.running)
        assertEquals(3, next.turns)
    }

    @Test
    fun pressWhileAPassRunsQueuesAndLeavesTheOrientationAlone() {
        var state = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        state = RotationQueue.press(state, clockwise = true)
        assertTrue(state.running)
        assertEquals(1, state.turns)   // the pass stays on its own orientation…
        assertEquals(1, state.queued)  // …the second press is queued behind it
        assertEquals(2, state.displayTurns)  // …and shown at once regardless
    }

    @Test
    fun fourPressesWhileAPassRunsQueueNothingAndCostNoFurtherPass() {
        var state = RotationQueue.State.IDLE
        state = RotationQueue.passStarted(state)          // the host's first OCR run
        repeat(4) { state = RotationQueue.press(state, clockwise = true) }
        assertEquals(0, state.queued)
        state = RotationQueue.passFinished(state)
        assertFalse(state.running)
        assertEquals(0, state.turns)
    }

    @Test
    fun threePressesWhileAPassRunsBecomeOneThreeQuarterPass() {
        var state = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        repeat(3) { state = RotationQueue.press(state, clockwise = true) }
        assertEquals(3, state.queued)
        state = RotationQueue.passFinished(state)
        assertTrue(state.running)       // one pass for all three, not three passes
        assertEquals(0, state.turns)    // 1 on screen + 3 queued
        assertEquals(0, state.queued)
    }

    @Test
    fun aBurstWhileAPassRunsCostsExactlyOneMorePass() {
        var state = RotationQueue.State.IDLE
        state = RotationQueue.passStarted(state)          // a pass is already in flight
        repeat(11) { state = RotationQueue.press(state, clockwise = true) }
        state = RotationQueue.passFinished(state)         // …it ends
        assertTrue(state.running)
        assertEquals(3, state.turns)                      // 11 = 2×4 + 3, net
        state = RotationQueue.passFinished(state)         // the one coalesced pass ends
        assertFalse(state.running)
    }

    @Test
    fun mixedPressesWhileAPassRunsCancelOut() {
        var state = RotationQueue.State.IDLE
        state = RotationQueue.press(state, clockwise = true)
        state = RotationQueue.press(state, clockwise = true)
        state = RotationQueue.press(state, clockwise = false)
        assertEquals(0, state.queued)
        state = RotationQueue.passFinished(state)
        assertFalse(state.running)
        assertEquals(1, state.turns)
    }

    @Test
    fun coalescedBurstLandsWherePressesOneAtATimeWouldHave() {
        val presses = listOf(true, true, true, true, true, false, true, false, false, true, true)
        // One at a time, a pass per press: the orientation after the last one.
        var oneAtATime = 0
        presses.forEach { oneAtATime = ImageRotation.turn(oneAtATime, it) }
        // The same presses, all landing while one pass is in flight.
        var coalesced = RotationQueue.State.IDLE
        coalesced = RotationQueue.passStarted(coalesced)
        presses.forEach { coalesced = RotationQueue.press(coalesced, it) }
        coalesced = RotationQueue.passFinished(coalesced)
        assertEquals(oneAtATime, coalesced.turns)
    }

    @Test
    fun aPressAfterTheQueueIdlesStartsExactlyOnePass() {
        var state = RotationQueue.State.IDLE
        state = RotationQueue.passStarted(state)
        state = RotationQueue.passFinished(state)         // the first run ends, untouched
        assertFalse(state.running)
        state = RotationQueue.press(state, clockwise = true)
        assertTrue(state.running)
        assertEquals(1, state.turns)
        assertEquals(0, state.queued)
    }

    @Test
    fun passStartedAlsoMakesPressesQueueWhenTheQueueWasIdle() {
        var state = RotationQueue.passStarted(RotationQueue.State.IDLE)
        state = RotationQueue.press(state, clockwise = true)
        assertEquals(0, state.turns)
        assertEquals(1, state.queued)
    }

    /**
     * The screen follows [RotationQueue.State.displayTurns] — the net asked for,
     * wrapped — not [RotationQueue.State.turns]. This is what makes a press land
     * on screen at once while a pass is still running, and it is the half the
     * pump got wrong: comparing `turns` alone let a press that had already been
     * shown go forgotten, and a run whose results the display had dropped was
     * never replaced.
     */
    @Test
    fun pressesWhileAPassRunsAreShownAtOnce() {
        var state = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        assertEquals(1, state.displayTurns)
        state = RotationQueue.press(state, clockwise = true)
        assertEquals(2, state.displayTurns)   // on screen now…
        assertEquals(1, state.turns)          // …while the running pass keeps its turn
        assertEquals(1, state.queued)
    }

    @Test
    fun oppositePressesWhileAPassRunsBringTheDisplayBackAtOnce() {
        var state = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        state = RotationQueue.press(state, clockwise = false)
        assertEquals(0, state.displayTurns)   // one then the other shows the start again
        assertEquals(3, state.queued)         // …queued behind the pass, working back to 0
        state = RotationQueue.passFinished(state)
        assertEquals(0, state.displayTurns)   // the fold lands on the same net
        assertEquals(0, state.turns)
        assertEquals(0, state.queued)
    }

    @Test
    fun displayTurnsMatchesWhatPressesOneAtATimeWouldHaveShown() {
        val presses = listOf(true, true, false, true, false, false, true)
        var oneAtATime = 0
        presses.forEach { oneAtATime = ImageRotation.turn(oneAtATime, it) }
        var coalesced = RotationQueue.passStarted(RotationQueue.State.IDLE)
        presses.forEach { coalesced = RotationQueue.press(coalesced, it) }
        assertEquals(oneAtATime, coalesced.displayTurns)
    }

    @Test
    fun displayTurnsIgnoresThePassesOwnTurnUntilItFolds() {
        // Two presses from rest: the first IS the pass (turns=1, queued=0), the
        // second queues. displayTurns is where the user has got to either way.
        var state = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        state = RotationQueue.press(state, clockwise = true)
        state = RotationQueue.passStarted(state)
        assertEquals(2, state.displayTurns)
        state = RotationQueue.passFinished(state)   // the pass ends; queued folds in
        assertEquals(2, state.displayTurns)         // the net never jumps
        assertEquals(2, state.turns)
        assertEquals(0, state.queued)
    }

    @Test
    fun passAbandonedParksTheQueueAndALaterPressStillStartsAPass() {
        var state = RotationQueue.press(RotationQueue.State.IDLE, clockwise = true)
        state = RotationQueue.press(state, clockwise = true)
        state = RotationQueue.passAbandoned(state)
        assertFalse(state.running)
        assertEquals(1, state.turns)                      // what is displayed is untouched
        assertEquals(0, state.queued)
        state = RotationQueue.press(state, clockwise = false)
        assertTrue(state.running)
        assertEquals(0, state.turns)
    }
}
