package app.torboxtv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimingTest {
    @Test fun chargesOnlyAdvancingPlayback() {
        val timing = PlaybackTiming()
        timing.reset(0, 0)
        assertEquals(1000L, timing.sample(1000, 1000, true))
        assertEquals(0L, timing.sample(1000, 2000, true))
        assertEquals(0L, timing.sample(2000, 3000, false))
        assertEquals(1000L, timing.sample(3000, 4000, true))
    }
    @Test fun forwardAndBackwardSeeksAreNotCharged() {
        val timing = PlaybackTiming()
        timing.reset(10_000, 0)
        assertEquals(0L, timing.sample(70_000, 1000, true))
        assertEquals(0L, timing.sample(5_000, 2000, true))
        assertEquals(1000L, timing.sample(6_000, 3000, true))
    }
    @Test fun suspendedTimerCannotSpendAnUnboundedAllowance() {
        val timing = PlaybackTiming()
        timing.reset(0, 0)
        assertEquals(2000L, timing.sample(60_000, 60_000, true))
    }
    @Test fun initialObservationAndClockReversalDoNotCharge() {
        val timing = PlaybackTiming()
        assertEquals(0L, timing.sample(500, 1000, true))
        assertEquals(0L, timing.sample(1000, 500, true))
    }
}
