package app.mazlens;

import static org.junit.Assert.*;

import app.mazlens.core.connection.ConnectionAttempt;
import org.junit.Test;

public class ConnectionAttemptTest {

  @Test
  public void failedCancelAndFailedRemovalKeepNewConnectsBlocked() {
    ConnectionAttempt operation = new ConnectionAttempt();
    int cleanup = operation.begin();
    assertFalse(operation.completeCleanup(cleanup, false, false));
    assertTrue(operation.isCurrent(cleanup));
    assertEquals(0, operation.begin());
  }

  @Test
  public void oldCallbacksCannotCompleteANewAttemptAfterCancellation() {
    ConnectionAttempt attempt = new ConnectionAttempt();
    int first = attempt.begin();
    attempt.invalidate();
    int cancellation = attempt.begin();
    assertFalse(attempt.finish(first));
    assertEquals(0, attempt.begin());
    assertTrue(attempt.isCurrent(cancellation));
    assertTrue(attempt.finish(cancellation));
    int retry = attempt.begin();
    assertTrue(retry > cancellation);
    assertFalse(attempt.finish(cancellation));
    assertTrue(attempt.isCurrent(retry));
  }
}
