package app.mazlens.app

import app.mazlens.core.protocol.AccessProbe.Mode
import app.mazlens.data.history.AttemptStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessConclusionTest {
    @Test
    fun stringStatusCannotBecomeGreenAfterStrictProtocolFailure() {
        assertEquals(
            AttemptStatus.FAILED,
            AccessConclusion.classify(
                """{"msg_id":1,"rval":"0","param":"value"}""",
                Mode.SINGLE_FIELD,
                false,
            ),
        )
    }

    @Test
    fun returnedUnrelatedFieldAndWrongPasswordTypeAreNotSuccess() {
        assertEquals(
            AttemptStatus.FAILED,
            AccessConclusion.classify(
                """{"msg_id":1,"rval":0,"type":"other","param":"value"}""",
                Mode.SINGLE_FIELD,
                false,
            ),
        )
        assertEquals(
            AttemptStatus.FAILED,
            AccessConclusion.classify(
                """{"msg_id":3,"rval":0,"w.pass":123}""",
                Mode.LEGACY_SESSION,
                true,
            ),
        )
    }

    @Test
    fun successfulTokenAloneIsNotSuccessfulSettingsRead() {
        assertEquals(
            AttemptStatus.FAILED,
            AccessConclusion.classify(
                """{"msg_id":257,"rval":0,"param":9}""",
                Mode.LEGACY_SESSION,
                true,
            ),
        )
    }

    @Test
    fun validReadWithoutPasswordStillMeansSettingsWereRead() {
        assertEquals(
            AttemptStatus.SUCCESS,
            AccessConclusion.classify("""{"msg_id":3,"rval":0}""", Mode.LEGACY_SESSION, true),
        )
        assertEquals(
            AttemptStatus.SUCCESS,
            AccessConclusion.classify(
                """{"msg_id":1,"rval":0,"type":"w.pass","param":""}""",
                Mode.SINGLE_FIELD,
                false,
            ),
        )
    }

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
