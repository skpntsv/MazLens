package app.mazlens.operation

import app.mazlens.core.operation.PlaybackRequestGate
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestGateTest {
    @Test
    fun closingPlayerRejectsAlreadyPendingLookup() {
        val gate = PlaybackRequestGate()
        val pending = gate.begin()
        gate.cancel()
        assertFalse(gate.canStart(pending, true, false))
    }

    @Test
    fun newerSelectionRejectsEarlierLookupEvenWhenItCompletesLast() {
        val gate = PlaybackRequestGate()
        val old = gate.begin()
        val latest = gate.begin()
        assertFalse(gate.canStart(old, true, false))
        assertTrue(gate.canStart(latest, true, false))
    }

    @Test
    fun backgroundInvalidationDoesNotResurrectOnForeground() {
        val gate = PlaybackRequestGate()
        val pending = gate.begin()
        gate.cancel()
        assertFalse(gate.canStart(pending, false, false))
        assertFalse(gate.canStart(pending, true, false))
    }
}
