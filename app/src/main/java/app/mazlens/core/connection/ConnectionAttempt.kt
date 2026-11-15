package app.mazlens.core.connection

/** Tracks the one outstanding Android operation and ignores stale callbacks. */
class ConnectionAttempt {
    private var generation = 0
    private var pending = false
    private var deadline = 0L

    fun begin(): Int = begin(0)

    fun begin(deadline: Long): Int {
        if (pending) return 0
        pending = true
        this.deadline = deadline
        return ++generation
    }

    fun isCurrent(id: Int): Boolean = pending && id == generation

    fun pending(): Boolean = pending

    fun deadline(): Long = if (pending) deadline else 0

    fun finish(id: Int): Boolean {
        if (!isCurrent(id)) return false
        pending = false
        return true
    }

    fun invalidate() {
        pending = false
        ++generation
    }

    fun completeCleanup(
        id: Int,
        cancellationSucceeded: Boolean,
        removalSucceeded: Boolean,
    ): Boolean {
        if (!cancellationSucceeded && !removalSucceeded) return false
        return finish(id)
    }
}
