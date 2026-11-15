package app.mazlens;

import static org.junit.Assert.*;

import app.mazlens.core.connection.WifiDirectController;
import org.junit.Test;

public class WifiDirectControllerTest {
  @Test
  public void choosingAnotherRecorderNeverAcceptsThePreviousKnownGroup() {
    assertFalse(
        WifiDirectController.acceptsOwner(
            "aa:bb:cc:00:00:02", "aa:bb:cc:00:00:01", "C9K1_V6_580", "DIRECT-C9K1"));
  }

  @Test
  public void explicitlySelectedOwnerWorksWithoutKnownModelName() {
    assertTrue(
        WifiDirectController.acceptsOwner(
            "AA:BB:CC:00:00:02", "aa:bb:cc:00:00:02", "Recorder", "DIRECT-xx"));
  }
}
