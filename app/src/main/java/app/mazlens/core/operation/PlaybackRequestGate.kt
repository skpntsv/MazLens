package app.mazlens.core.operation

/** Identifies an explicitly requested local-file lookup across suspend/stop boundaries. */
class PlaybackRequestGate {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun cancel() {
        generation++
    }

    fun canStart(ticket: Long, foreground: Boolean, busy: Boolean): Boolean =
        ticket == generation && foreground && !busy
}
