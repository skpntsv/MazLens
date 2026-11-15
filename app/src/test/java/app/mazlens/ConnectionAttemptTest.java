package app.mazlens;

import static org.junit.Assert.*;

import app.mazlens.core.connection.ConnectionAttempt;
import org.junit.Test;

public class ConnectionAttemptTest {
  @Test
  public void duplicateTapCannotMoveTheDeadlineAcrossActivityRecreation() {
    ConnectionAttempt oldController = new ConnectionAttempt();
    oldController.begin(30000);
    oldController.begin(48000);
    ConnectionAttempt replacement = new ConnectionAttempt();
    int restored = replacement.begin(oldController.deadline());
    assertTrue(replacement.isCurrent(restored));
    assertEquals(30000, replacement.deadline());
    assertEquals(0, replacement.begin(60000));
    assertEquals(30000, replacement.deadline());
  }

  @Test
  public void failedCancelAndFailedRemovalKeepNewConnectsBlocked() {
    ConnectionAttempt operation = new ConnectionAttempt();
    int cleanup = operation.begin();
    assertFalse(operation.completeCleanup(cleanup, false, false));
    assertTrue(operation.isCurrent(cleanup));
    assertEquals(0, operation.begin());
  }

  @Test
  public void confirmedCancellationOrRemovalAllowsAnotherAttempt() {
    for (boolean cancellation : new boolean[] {false, true}) {
      for (boolean removal : new boolean[] {false, true}) {
        if (!cancellation && !removal) continue;
        ConnectionAttempt operation = new ConnectionAttempt();
        int cleanup = operation.begin();
        assertTrue(operation.completeCleanup(cleanup, cancellation, removal));
        assertTrue(operation.begin() > cleanup);
      }
    }
  }

  @Test
  public void repeatedTapsDoNotReplaceTheOriginalTimeout() {
    ConnectionAttempt attempt = new ConnectionAttempt();
    int first = attempt.begin();
    assertTrue(first > 0);
    assertEquals(0, attempt.begin());
    assertEquals(0, attempt.begin());
    assertTrue(attempt.isCurrent(first));
    assertTrue(attempt.pending());
    assertTrue(attempt.finish(first));
    assertFalse(attempt.pending());
  }

  @Test
  public void callbackFromRejectedDuplicateCannotUnlockPendingOperation() {
    ConnectionAttempt attempt = new ConnectionAttempt();
    int first = attempt.begin();
    int duplicate = attempt.begin();
    assertFalse(attempt.finish(duplicate));
    assertTrue(attempt.isCurrent(first));
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
