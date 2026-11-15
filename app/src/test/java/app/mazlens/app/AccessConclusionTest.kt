package app.mazlens.app

import app.mazlens.core.protocol.AccessProbe.Mode
import app.mazlens.data.history.AttemptStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessConclusionTest {

    @Test
    fun validDenialKeepsRejectionAndUnownedSettingsCannotBeSuccess() {
        assertEquals(
            AttemptStatus.REJECTED,
            AccessConclusion.classify("""{"msg_id":257,"rval":-29}""", Mode.MODEL_ONLY, false),
        )
        assertEquals(
            AttemptStatus.FAILED,
            AccessConclusion.classify("""{"msg_id":3,"rval":0}""", Mode.MODEL_ONLY, false),
        )
    }
}
