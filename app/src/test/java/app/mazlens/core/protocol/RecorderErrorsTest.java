package app.mazlens.core.protocol;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.junit.Test;

public class RecorderErrorsTest {
  @Test
  public void explainsKnownForcedDisconnectReasonsWithoutShowingRemoteText() throws Exception {
    Class<?> type = Class.forName("app.mazlens.core.protocol.RecorderErrors");
    Object instance = type.getField("INSTANCE").get(null);
    Method describe;
    try {
      describe = type.getMethod("disconnected", String.class);
    } catch (NoSuchMethodException missing) {
      fail("Forced-disconnect explanations are not implemented");
      return;
    }
    String speed = (String) describe.invoke(instance, "speed_err");
    assertTrue(speed, speed.contains("GPS") && speed.contains("движ"));
    assertTrue(((String) describe.invoke(instance, "session_timeout")).contains("служебн"));
    assertTrue(((String) describe.invoke(instance, "sdcard_err")).contains("SD"));
    assertTrue(
        ((String) describe.invoke(instance, "navi_priority")).toLowerCase().contains("навигац"));
    String unknown = (String) describe.invoke(instance, "private-remote-value");
    assertFalse(unknown, unknown.contains("private-remote-value"));
  }

  // Catches treating a processing or GPS/speed refusal as a password error.
  @Test
  public void separatesProcessingSpeedSdAndPasswordRefusals() throws Exception {
    Class<?> type;
    try {
      type = Class.forName("app.mazlens.core.protocol.RecorderErrors");
    } catch (ClassNotFoundException missing) {
      fail("Recorder refusal descriptions are not implemented");
      return;
    }
    Object instance = type.getField("INSTANCE").get(null);
    Method describe = type.getMethod("describe", int.class);
    String busy = (String) describe.invoke(instance, -51);
    String speed = (String) describe.invoke(instance, -52);
    String password = (String) describe.invoke(instance, -29);
    String sd = (String) describe.invoke(instance, -53);
    assertTrue(busy, busy.contains("обрабатывает") || busy.contains("занят"));
    assertTrue(speed, speed.contains("GPS") && speed.contains("движ"));
    assertTrue(password, password.toLowerCase().contains("парол"));
    assertTrue(sd, sd.contains("SD"));
    assertFalse(busy, busy.toLowerCase().contains("парол"));
    assertFalse(speed, speed.toLowerCase().contains("парол"));
    assertTrue(((String) describe.invoke(instance, -999)).contains("-999"));
  }
}
