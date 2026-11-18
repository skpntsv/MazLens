package app.mazlens.core.operation

/** An operation keeps its slot until sockets/player and durable result finish. */
class OperationGate {
    data class Ticket(val id: Long, val connectionEpoch: Long)

    private var generation = 0L
    private var current: Ticket? = null
    private var cancelled = false

    @Synchronized
    fun begin(epoch: Long): Ticket? {
        if (current != null) return null
        return Ticket(++generation, epoch).also {
            current = it
            cancelled = false
        }
    }

    @Synchronized
    fun canContinue(ticket: Ticket, epoch: Long): Boolean =
        current == ticket && !cancelled && ticket.connectionEpoch == epoch

    @Synchronized
    fun cancel(ticket: Ticket) {
        if (current == ticket) cancelled = true
    }

    /**
     * Release after cleanup/persistence and return whether the terminal result is still current.
     */
    @Synchronized
    fun finish(ticket: Ticket, epoch: Long): Boolean {
        if (current != ticket) return false
        val publish = canContinue(ticket, epoch)
        current = null
        cancelled = false
        return publish
    }

    @Synchronized fun active() = current != null

    @Synchronized
    fun publishIfCurrent(ticket: Ticket, epoch: () -> Long, publication: () -> Unit): Boolean {
        if (!canContinue(ticket, epoch())) return false
        publication()
        return true
    }
}
