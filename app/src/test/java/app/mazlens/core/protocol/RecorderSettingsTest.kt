package app.mazlens.core.protocol

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecorderSettingsTest {
    @Test
    fun editsAndDefaultsPreserveCredentialsAndCameraConfiguration() {
        val original = RecorderSettings.parse(settingsReply())
        val changed =
            original
                .with(RecorderSetting.PARKING_DURATION, 4)
                .with(RecorderSetting.PARKING_START, 6)
                .with(RecorderSetting.PARKING_END, 6)
        val request = changed.request(23)
        assertEquals(4, request.getInt("p.time.e.15s"))
        assertEquals(6, request.getInt("p.start.e.30s"))
        assertEquals(6, request.getInt("p.stop.e.30s"))
        assertEquals(1, request.getInt("s.cam"))
        assertEquals("OwnerPwd", request.getString("w.pass"))
        assertEquals(268435457, request.getInt("msg_id"))
        assertEquals(23, request.getInt("token"))
        assertEquals(
            RecorderSetting.entries.map { it.wireKey }.toSet() +
                setOf("s.cam", "w.pass", "msg_id", "token"),
            request.keys().asSequence().toSet(),
        )
        val defaults = changed.withDefaults()
        RecorderSetting.entries.forEach { assertEquals(it.defaultValue, defaults[it]) }
        assertEquals(1, defaults.cameraMode)
        assertTrue(defaults.hasPassword("OwnerPwd"))
        assertTrue(original.hasPassword("OwnerPwd"))
        assertFalse(original.withPassword("NewPwd123").matches(original))
    }

    @Test
    fun refusesMissingUnknownAndMalformedValuesInsteadOfDefaulting() {
        for (setting in RecorderSetting.entries) {
            val missing = settingsReply().apply { remove(setting.wireKey) }
            assertThrows(IOException::class.java) { RecorderSettings.parse(missing) }
            for (value in
                listOf(JSONObject.NULL, "1.5", " 1", "01", "1x", true, 1.5, -1, 10000000000L)) {
                val invalid = settingsReply().put(setting.wireKey, value)
                assertThrows(IOException::class.java) { RecorderSettings.parse(invalid) }
            }
        }
        for (key in listOf("s.cam", "w.pass")) {
            assertThrows(IOException::class.java) {
                RecorderSettings.parse(settingsReply().apply { remove(key) })
            }
        }
        for (value in listOf(2, true, "1.0", JSONObject.NULL)) {
            assertThrows(IOException::class.java) {
                RecorderSettings.parse(settingsReply().put("s.cam", value))
            }
        }
        for (value in listOf("********", "", "short", 12345678, JSONObject.NULL)) {
            assertThrows(IOException::class.java) {
                RecorderSettings.parse(settingsReply().put("w.pass", value))
            }
        }
    }

    @Test
    fun canonicalNumericSettingStringsAreValidatedAndMetadataIsNeverEchoedIntoUpdates() {
        val json = settingsReply()
        RecorderSetting.entries.forEach { json.put(it.wireKey, it.defaultValue.toString()) }
        json.put("s.cam", "1").put("s.ver", "unexpected private text").put("unknown", "private")
        val snapshot = RecorderSettings.parse(json)
        val request = snapshot.request(23)
        assertEquals("", snapshot.firmware)
        assertEquals(1, snapshot.cameraMode)
        assertFalse(request.has("unknown"))
        assertFalse(request.has("s.ver"))
        RecorderSetting.entries.forEach {
            assertEquals(it.defaultValue, request.getInt(it.wireKey))
        }
    }
}

internal fun settingsReply(): JSONObject =
    JSONObject()
        .put("msg_id", 3)
        .put("rval", 0)
        .put("s.cam", 1)
        .put("w.pass", "OwnerPwd")
        .put("s.ver", "1.01")
        .also { json -> RecorderSetting.entries.forEach { json.put(it.wireKey, it.defaultValue) } }
