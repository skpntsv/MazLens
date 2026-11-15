package app.mazlens;

import static org.junit.Assert.*;

import app.mazlens.core.protocol.JsonObjectReader;
import app.mazlens.core.protocol.ProbeResult;
import app.mazlens.core.protocol.RecorderProbe;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;

public class RecorderProbeTest {
  @Test
  public void untrustedNotificationIdsAreNotPrintedIntoTheDiagnosticLog() throws Exception {
    java.util.List<String> log = new java.util.ArrayList<>();
    try (FakeRecorder server =
        new FakeRecorder(
            "{\"msg_id\":{\"w.pass\":\"private-marker\"}}{\"msg_id\":3,\"rval\":-4}")) {
      ProbeResult result =
          new RecorderProbe(Socket::new, log::add)
              .query("127.0.0.1", server.commandPort(), server.dataPort(), 1000);
      assertFalse(result.success());
      assertFalse(String.join("\n", log).contains("private-marker"));
    }
  }

  @Test
  public void readsOneSettingsRequestAndSkipsAnUnrelatedNotification() throws Exception {
    try (FakeRecorder server =
        new FakeRecorder(
            "{\"msg_id\":7,\"rval\":0}{\"msg_id\":3,\"rval\":0,\"w.pass\":\"aB7cD9eF\"}")) {
      ProbeResult result =
          new RecorderProbe(Socket::new, value -> {})
              .query("127.0.0.1", server.commandPort(), server.dataPort(), 1000);
      assertNotNull("A recorder response must produce a result", result);
      assertTrue(result.success());
      assertEquals("aB7cD9eF", result.passwordValue());
      assertEquals("{\"token\":0,\"msg_id\":3}", server.request.get(1, TimeUnit.SECONDS));
      assertTrue(
          "Must not issue a second recorder command",
          server.noSecondCommand.get(1, TimeUnit.SECONDS));
    }
  }

  @Test
  public void framesQuotedBracesAndConsecutiveObjects() throws Exception {
    String first = "{\"msg_id\":7,\"param\":\"a } { \\\" quoted \\\\ path\"}";
    JsonObjectReader reader =
        new JsonObjectReader(new StringReader(" \n" + first + "{\"msg_id\":3}"), 4096);
    assertEquals(first, reader.next());
    assertEquals("{\"msg_id\":3}", reader.next());
  }

  @Test
  public void recorderDenialNeverCountsAsARecoveredPassword() throws Exception {
    ProbeResult result = ProbeResult.parse("{\"msg_id\":3,\"rval\":-4,\"w.pass\":\"ignored\"}");
    assertNotNull(result);
    assertFalse(result.success());
    assertEquals("REJECTED", result.state());
    assertEquals("", result.passwordValue());
  }

  @Test
  public void preservesMissingNullEmptyAndMaskedFieldsAsDifferentOutcomes() throws Exception {
    String[] fields = {"", ",\"w.pass\":null", ",\"w.pass\":\"\"", ",\"w.pass\":\"********\""};
    String[] states = {"MISSING", "NULL", "EMPTY", "MASKED"};
    for (int i = 0; i < fields.length; i++) {
      ProbeResult result = ProbeResult.parse("{\"msg_id\":3,\"rval\":0" + fields[i] + "}");
      assertNotNull(result);
      assertEquals(states[i], result.state());
      assertFalse(result.hasCandidate());
    }
  }

  @Test
  public void invalidOrMissingStatusIsNotASettingsSuccess() throws Exception {
    for (String value :
        new String[] {
          "{}",
          "{\"msg_id\":3}",
          "{\"msg_id\":7,\"rval\":0}",
          "{\"msg_id\":3,\"rval\":\"oops\"}",
          "{\"msg_id\":3,\"rval\":0,\"w.pass\":123}"
        }) {
      ProbeResult result = ProbeResult.parse(value);
      assertNotNull(result);
      assertFalse(result.success());
      assertFalse(result.hasCandidate());
    }
  }

  @Test
  public void limitsFrameSizeAndRejectsTruncatedObjects() throws Exception {
    for (String input :
        new String[] {"{\"message\":\"" + "x".repeat(40) + "\"}", "{\"msg_id\":3"}) {
      boolean rejected = false;
      try {
        new JsonObjectReader(new StringReader(input), 32).next();
      } catch (IOException expected) {
        rejected = true;
      }
      assertTrue("Incomplete or oversized response must be rejected", rejected);
    }
  }

  @Test
  public void stillQueriesSettingsWhenTheCompanionDataPortIsClosed() throws Exception {
    try (FakeRecorder server = new FakeRecorder("{\"msg_id\":3,\"rval\":0}")) {
      int closedPort;
      try (ServerSocket temporary = new ServerSocket(0)) {
        closedPort = temporary.getLocalPort();
      }
      ProbeResult result =
          new RecorderProbe(Socket::new, value -> {})
              .query("127.0.0.1", server.commandPort(), closedPort, 1000);
      assertNotNull(result);
      assertTrue(result.success());
      assertEquals("MISSING", result.state());
    }
  }

  @Test
  public void stopsWaitingForASilentRecorder() throws Exception {
    try (FakeRecorder server = new FakeRecorder(null)) {
      long start = System.nanoTime();
      boolean timedOut = false;
      try {
        new RecorderProbe(Socket::new, value -> {})
            .query("127.0.0.1", server.commandPort(), server.dataPort(), 150);
      } catch (SocketTimeoutException expected) {
        timedOut = true;
      }
      assertTrue("A silent recorder must time out", timedOut);
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
    }
  }

  @Test
  public void cancellationClosesAnInProgressRead() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (FakeRecorder server = new FakeRecorder(null);
        RecorderProbe probe = new RecorderProbe(Socket::new, value -> {})) {
      Future<ProbeResult> result =
          worker.submit(
              () -> probe.query("127.0.0.1", server.commandPort(), server.dataPort(), 3000));
      if (result.isDone()) assertNotNull("Missing implementation", result.get());
      server.request.get(1, TimeUnit.SECONDS);
      probe.close();
      boolean cancelled = false;
      try {
        result.get(1, TimeUnit.SECONDS);
      } catch (ExecutionException error) {
        cancelled = error.getCause() instanceof IOException;
      }
      assertTrue("Cancellation must interrupt the blocked read", cancelled);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void ongoingTrafficCannotExtendTheReadDeadlineForever() throws Exception {
    try (FakeRecorder server = new FakeRecorder("DRIBBLE")) {
      long start = System.nanoTime();
      boolean timedOut = false;
      try {
        new RecorderProbe(Socket::new, value -> {})
            .query("127.0.0.1", server.commandPort(), server.dataPort(), 150);
      } catch (SocketTimeoutException expected) {
        timedOut = true;
      }
      assertTrue("Read deadline must apply even while notifications arrive", timedOut);
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
    }
  }

  private static final class FakeRecorder implements AutoCloseable {
    final ServerSocket commands = new ServerSocket(0);
    final ServerSocket data = new ServerSocket(0);
    final CompletableFuture<String> request = new CompletableFuture<>();
    final CompletableFuture<Boolean> noSecondCommand = new CompletableFuture<>();
    final CountDownLatch finish = new CountDownLatch(1);
    final ExecutorService workers = Executors.newFixedThreadPool(2);
    volatile Socket commandSocket, dataSocket;

    FakeRecorder(String response) throws IOException {
      workers.submit(
          () -> {
            try (Socket socket = commands.accept()) {
              commandSocket = socket;
              StringBuilder sent = new StringBuilder();
              int next;
              while ((next = socket.getInputStream().read()) != -1) {
                sent.append((char) next);
                if (next == '}') break;
              }
              request.complete(sent.toString());
              if (response == null) {
                finish.await();
                return;
              }
              if (response.equals("DRIBBLE")) {
                while (finish.getCount() > 0) {
                  socket
                      .getOutputStream()
                      .write("{\"msg_id\":7,\"rval\":0}".getBytes(StandardCharsets.UTF_8));
                  socket.getOutputStream().flush();
                  Thread.sleep(20);
                }
                return;
              }
              byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
              for (int i = 0; i < bytes.length; i += 3) {
                socket.getOutputStream().write(bytes, i, Math.min(3, bytes.length - i));
                socket.getOutputStream().flush();
              }
              socket.setSoTimeout(1000);
              noSecondCommand.complete(socket.getInputStream().read() == -1);
            } catch (Exception error) {
              request.completeExceptionally(error);
              noSecondCommand.completeExceptionally(error);
            }
          });
      workers.submit(
          () -> {
            try (Socket socket = data.accept()) {
              dataSocket = socket;
              finish.await();
            } catch (Exception ignored) {
            }
          });
    }

    int commandPort() {
      return commands.getLocalPort();
    }

    int dataPort() {
      return data.getLocalPort();
    }

    public void close() throws IOException {
      finish.countDown();
      commands.close();
      data.close();
      if (commandSocket != null) commandSocket.close();
      if (dataSocket != null) dataSocket.close();
      workers.shutdownNow();
    }
  }
}
