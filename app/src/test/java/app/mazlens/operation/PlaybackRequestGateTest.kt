package app.mazlens.operation

import app.mazlens.core.operation.PlaybackRequestGate
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestGateTest {

    @Test
    fun backgroundInvalidationDoesNotResurrectOnForeground() {
        val gate = PlaybackRequestGate()
        val pending = gate.begin()
        gate.cancel()
        assertFalse(gate.canStart(pending, false, false))
        assertFalse(gate.canStart(pending, true, false))
    }
}
