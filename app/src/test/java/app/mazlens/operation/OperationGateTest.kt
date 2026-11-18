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
        assertFalse(gate.finish(ticket, 10))
        assertNotNull(gate.begin(10))
    }
}
