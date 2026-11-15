package app.mazlens.app

import app.mazlens.data.history.AttemptStatus

object VideoConclusion {
    fun status(
        success: Boolean,
        canContinue: Boolean,
        userStopped: Boolean = false,
    ): AttemptStatus =
        if (success) AttemptStatus.SUCCESS
        else if (userStopped || !canContinue) AttemptStatus.CANCELLED else AttemptStatus.FAILED
}
