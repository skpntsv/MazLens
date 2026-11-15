package app.mazlens.operation

import app.mazlens.core.operation.OperationGate
import org.junit.Assert.*
import org.junit.Test

class OperationGateTest {
    @Test
    fun cancellationAndChangedConnectionPreventAtomicPublication() {
        val gate = OperationGate()
        val ticket = gate.begin(4)!!
        var published = 0
        assertFalse(gate.publishIfCurrent(ticket, { 5 }) { published++ })
        gate.cancel(ticket)
        assertFalse(gate.publishIfCurrent(ticket, { 4 }) { published++ })
        assertEquals(0, published)
    }

    @Test
    fun preventsOverlappingAccessAndVideoWorkUntilFinalization() {
        val gate = OperationGate()
        val ticket = gate.begin(10)!!
        assertTrue(gate.active())
        assertNull(gate.begin(10))
        gate.cancel(ticket)
        assertNull(gate.begin(10))
        assertTrue(gate.finish(ticket))
        assertNotNull(gate.begin(10))
    }

    @Test
    fun staleCallbackCannotReleaseNewerOperation() {
        val gate = OperationGate()
        val old = gate.begin(1)!!
        assertTrue(gate.finish(old))
        val newer = gate.begin(1)!!
        assertFalse(gate.finish(old))
        assertTrue(gate.canContinue(newer, 1))
        assertTrue(gate.active())
    }

    @Test
    fun connectionChangePreventsProbeToPlayerTransition() {
        val gate = OperationGate()
        val ticket = gate.begin(4)!!
        assertTrue(gate.canContinue(ticket, 4))
        assertFalse(gate.canContinue(ticket, 5))
    }

    @Test
    fun cancellationPreventsLateSuccessWithoutOpeningNextSlot() {
        val gate = OperationGate()
        val ticket = gate.begin(4)!!
        gate.cancel(ticket)
        assertFalse(gate.canContinue(ticket, 4))
        assertTrue(gate.active())
    }
}
