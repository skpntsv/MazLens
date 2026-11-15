package app.mazlens;

import static org.junit.Assert.*;

import app.mazlens.core.protocol.AccessProbe;
import app.mazlens.core.protocol.JsonObjectReader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;

public class AccessProbeTest {
  @Test
  public void knownReadinessRefusalsExplainGpsAndProcessingWithoutSuggestingPasswordFailure()
      throws Exception {
    for (int code : new int[] {-51, -52}) {
      try (Recorder r = new Recorder("{\"msg_id\":257,\"rval\":" + code + "}")) {
        AccessProbe.Outcome result =
            probe()
                .queryWithPassword(
                    "127.0.0.1", r.port(), r.dataPort(), "C9K1_V6_580", "TestPass", 700);
        assertTrue(result.summary, result.summary.contains(code == -52 ? "GPS" : "обрабатывает"));
        assertEquals(1, r.done.get(2, TimeUnit.SECONDS).size());
      }
    }
  }

  @Test
  public void suppliedPasswordUsesExactEscapedCredentialAndOwnedSession() throws Exception {
    String supplied = "Ab9\"\\CdE";
    List<String> logs = new ArrayList<>();
    try (Recorder r =
        new Recorder(
            "{\"msg_id\":257,\"rval\":0,\"param\":23}",
            "{\"msg_id\":3,\"rval\":0}",
            "{\"msg_id\":258,\"rval\":0}")) {
      AccessProbe.Outcome result =
          new AccessProbe(Socket::new, logs::add)
              .queryWithPassword("127.0.0.1", r.port(), r.dataPort(), "C9K1_V6_580", supplied, 700);
      assertTrue(result.summary, result.sessionOpened);
      List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
      assertEquals(3, commands.size());
      assertTrue(commands.get(0).has("password"));
      assertEquals(supplied, commands.get(0).getString("password"));
      assertEquals("C9K1_V6_580", commands.get(0).getString("model_name"));
      assertEquals(23, commands.get(1).getInt("token"));
      assertEquals(3, commands.get(1).getInt("msg_id"));
      assertEquals(23, commands.get(2).getInt("token"));
      assertEquals(258, commands.get(2).getInt("msg_id"));
      String sent =
          Arrays.stream(result.trace.split("\n"))
              .filter(line -> line.startsWith("SEND "))
              .findFirst()
              .get()
              .substring(5);
      assertEquals("<скрыто>", new JSONObject(sent).getString("password"));
      assertFalse(String.join("\n", logs).contains(supplied));
    }
  }

  @Test
  public void suppliedWrongPasswordNeverFallsBackToPasswordOmission() throws Exception {
    try (Recorder r = new Recorder("{\"msg_id\":257,\"rval\":-29}")) {
      AccessProbe.Outcome result =
          probe()
              .queryWithPassword(
                  "127.0.0.1", r.port(), r.dataPort(), "C9K1_V6_580", "WrongPwd", 700);
      assertFalse(result.sessionOpened);
      assertTrue(result.summary.contains("-29"));
      List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
      assertEquals(1, commands.size());
      assertEquals("WrongPwd", commands.get(0).optString("password"));
    }
  }

  @Test
  public void invalidSuppliedPasswordLengthIsRejectedBeforeAnySocket() throws Exception {
    java.util.concurrent.atomic.AtomicInteger opened =
        new java.util.concurrent.atomic.AtomicInteger();
    for (String value : new String[] {"", "short", "x".repeat(32)}) {
      AccessProbe p =
          new AccessProbe(
              () -> {
                opened.incrementAndGet();
                throw new IOException("must not connect");
              },
              s -> {});
      try {
        p.queryWithPassword("127.0.0.1", 7878, 8787, "C9K1_V6_580", value, 700);
        fail("invalid credential accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
    assertEquals(0, opened.get());
  }

  @Test
  public void delayedNonJsonTailIsRetainedPrivatelyWithoutAnotherCommand() throws Exception {
    String reply = "NOT JSON private-tail-marker";
    List<String> logged = new ArrayList<>();
    try (Recorder r = new Recorder(8, reply)) {
      AccessProbe.Outcome result =
          new AccessProbe(Socket::new, logged::add)
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  1000);
      assertFalse(result.sessionOpened);
      assertTrue(result.trace, result.trace.contains(reply));
      assertFalse(String.join("\n", logged).contains("private-tail-marker"));
      assertEquals(1, r.done.get(1, TimeUnit.SECONDS).size());
    }
  }

  @Test
  public void malformedReplyDiagnosticCaptureDoesNotWaitForTheWholeReplyTimeout() throws Exception {
    try (Recorder r = new Recorder(1000, "NOT JSON")) {
      long began = System.nanoTime();
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  3000);
      assertTrue(result.trace, result.trace.contains("NOT"));
      assertTrue(
          "Diagnostic capture must have its own short bound",
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 700);
    }
  }

  @Test
  public void malformedReplyDiagnosticCaptureCannotExtendTheOriginalReplyDeadline()
      throws Exception {
    try (Recorder r = new Recorder(40, "NOT JSON")) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  50);
      assertTrue(result.trace, result.trace.contains("NOT"));
      assertFalse(
          "A tail arriving after the original deadline must not be read",
          result.trace.contains("NOT JSON"));
    }
  }

  @Test
  public void malformedReplyDiagnosticCaptureKeepsTheSharedInputBudget() throws Exception {
    String reply = "N" + "x".repeat(128 * 1024) + "beyond-budget-marker";
    try (Recorder r = new Recorder(0, reply.length(), reply)) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  2000);
      String marker = "RAW INPUT (включая неполные сообщения):\n";
      assertTrue(result.trace.contains(marker));
      String captured = result.trace.substring(result.trace.indexOf(marker) + marker.length());
      assertEquals(
          "The aggregate input budget includes the initial parser read",
          128 * 1024 + 1,
          captured.length());
      assertFalse(captured.contains("beyond-budget-marker"));
    }
  }

  @Test
  public void cancellingMalformedReplyDiagnosticCaptureInterruptsTheRead() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Recorder r = new Recorder(1000, "NOT JSON");
        AccessProbe p = probe()) {
      Future<AccessProbe.Outcome> future =
          worker.submit(
              () ->
                  p.query(
                      "127.0.0.1",
                      r.port(),
                      r.dataPort(),
                      AccessProbe.Mode.LEGACY_SESSION,
                      "C9K1_V6_580",
                      3000));
      r.firstReplyChunk.get(1, TimeUnit.SECONDS);
      Thread.sleep(30);
      p.close();
      AccessProbe.Outcome result = future.get(250, TimeUnit.MILLISECONDS);
      assertTrue(result.summary, result.summary.contains("отменена"));
      assertFalse(result.sessionOpened);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void partialCleanupReplyIsRetainedWithoutHidingTheSettingsResult() throws Exception {
    try (Recorder r =
        new Recorder(
            "{\"msg_id\":257,\"rval\":0,\"param\":5}",
            "{\"msg_id\":3,\"rval\":0,\"w.pass\":\"owner-secret\"}",
            "{\"msg_id\":258,\"note\":\"cleanup-marker\"")) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  700);
      assertNotNull(result);
      assertEquals("owner-secret", result.password);
      assertTrue(result.trace, result.trace.contains("cleanup-marker"));
    }
  }

  @Test
  public void incompleteAndNonJsonRepliesAreRetainedForOfflineInvestigation() throws Exception {
    for (String reply : new String[] {"{\"msg_id\":257", "NOT JSON"}) {
      try (Recorder r = new Recorder(reply)) {
        AccessProbe.Outcome result =
            probe()
                .query(
                    "127.0.0.1",
                    r.port(),
                    r.dataPort(),
                    AccessProbe.Mode.LEGACY_SESSION,
                    "C9K1_V6_580",
                    100);
        assertNotNull(result);
        assertFalse(result.sessionOpened);
        assertTrue(result.trace, result.trace.contains(reply));
      }
    }
  }

  @Test
  public void untrustedErrorDetailsStayInPrivateTraceNotTheDiagnosticLog() throws Exception {
    List<String> logged = new ArrayList<>();
    AccessProbe probe =
        new AccessProbe(
            () -> {
              throw new IOException("w.pass=private-marker");
            },
            logged::add);
    AccessProbe.Outcome result =
        probe.query("127.0.0.1", 7878, 8787, AccessProbe.Mode.LEGACY_SESSION, "C9K1_V6_580", 100);
    assertTrue(result.trace.contains("private-marker"));
    assertFalse(String.join("\n", logged).contains("private-marker"));
  }

  @Test
  public void arbitraryObjectsInMessageIdsAreNotPrintedToLogcat() throws Exception {
    List<String> logged = new ArrayList<>();
    try (Recorder r =
        new Recorder(
            "{\"msg_id\":{\"w.pass\":\"private-marker\"},\"rval\":{\"token\":\"private-token\"}}{\"msg_id\":257,\"rval\":-29}")) {
      AccessProbe.Outcome result =
          new AccessProbe(Socket::new, logged::add)
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  700);
      assertTrue(result.trace.contains("private-marker"));
      assertFalse(String.join("\n", logged).contains("private-marker"));
      assertFalse(String.join("\n", logged).contains("private-token"));
    }
  }

  @Test
  public void legacySessionUsesOnlyReturnedTokenThenReleasesIt() throws Exception {
    try (Recorder r =
        new Recorder(
            "{\"msg_id\":257,\"rval\":0,\"param\":23}",
            "{\"msg_id\":3,\"rval\":0,\"w.pass\":\"owner-secret\"}",
            "{\"msg_id\":258,\"rval\":0}")) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  700);
      assertNotNull(result);
      assertTrue(result.summary + "\n" + result.trace, result.sessionOpened);
      assertEquals("owner-secret", result.password);
      List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
      assertEquals(3, commands.size());
      assertEquals(257, commands.get(0).getInt("msg_id"));
      assertEquals(0, commands.get(0).getInt("token"));
      assertFalse(commands.get(0).has("model_name"));
      assertFalse(commands.get(0).has("password"));
      assertEquals(3, commands.get(1).getInt("msg_id"));
      assertEquals(23, commands.get(1).getInt("token"));
      assertEquals(258, commands.get(2).getInt("msg_id"));
      assertEquals(23, commands.get(2).getInt("token"));
      assertTrue(result.trace.contains("owner-secret"));
    }
  }

  @Test
  public void omittedPasswordAndExplicitEmptyPasswordAreDifferentManualVariants() throws Exception {
    for (AccessProbe.Mode mode :
        new AccessProbe.Mode[] {AccessProbe.Mode.MODEL_ONLY, AccessProbe.Mode.EMPTY_PASSWORD}) {
      try (Recorder r = new Recorder("{\"msg_id\":257,\"rval\":-29}")) {
        AccessProbe.Outcome result =
            probe().query("127.0.0.1", r.port(), r.dataPort(), mode, "C9K1_V6_580", 700);
        assertNotNull(result);
        assertFalse(result.sessionOpened);
        assertEquals("", result.password);
        assertTrue(result.trace.contains("-29"));
        List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
        assertEquals(1, commands.size());
        assertEquals("C9K1_V6_580", commands.get(0).getString("model_name"));
        assertEquals(mode == AccessProbe.Mode.EMPTY_PASSWORD, commands.get(0).has("password"));
        if (commands.get(0).has("password"))
          assertEquals("", commands.get(0).getString("password"));
      }
    }
  }

  @Test
  public void invalidTokensNeverCauseAReadOrCleanupWithAFabricatedToken() throws Exception {
    for (String token : new String[] {"0", "-1", "1.5", "\"12\"", "null"}) {
      try (Recorder r = new Recorder("{\"msg_id\":257,\"rval\":0,\"param\":" + token + "}")) {
        AccessProbe.Outcome result =
            probe()
                .query(
                    "127.0.0.1",
                    r.port(),
                    r.dataPort(),
                    AccessProbe.Mode.LEGACY_SESSION,
                    "C9K1_V6_580",
                    700);
        assertNotNull(result);
        assertFalse(result.sessionOpened);
        assertEquals("", result.password);
        assertEquals(1, r.done.get(2, TimeUnit.SECONDS).size());
      }
    }
  }

  @Test
  public void singleFieldReadNeverStartsASession() throws Exception {
    try (Recorder r =
        new Recorder(
            "{\"msg_id\":7,\"type\":\"notice\"}{\"msg_id\":1,\"rval\":0,\"param\":\"field-value\"}")) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.SINGLE_FIELD,
                  "C9K1_V6_580",
                  700);
      assertNotNull(result);
      assertFalse(result.sessionOpened);
      assertEquals("field-value", result.password);
      List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
      assertEquals(1, commands.size());
      assertEquals(1, commands.get(0).getInt("msg_id"));
      assertEquals(0, commands.get(0).getInt("token"));
      assertEquals("w.pass", commands.get(0).getString("type"));
      assertTrue(result.trace.contains("notice"));
    }
  }

  @Test
  public void settingsDenialStillReleasesOnlyTheOwnedSession() throws Exception {
    try (Recorder r =
        new Recorder(
            "{\"msg_id\":257,\"rval\":0,\"param\":8}",
            "{\"msg_id\":3,\"rval\":-4}",
            "{\"msg_id\":258,\"rval\":0}")) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.MODEL_ONLY,
                  "C9K1_V6_580",
                  700);
      assertNotNull(result);
      assertTrue(result.sessionOpened);
      assertEquals("", result.password);
      assertTrue(result.trace.contains("-4"));
      List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
      assertEquals(3, commands.size());
      assertEquals(8, commands.get(2).getInt("token"));
    }
  }

  @Test
  public void timeoutPreservesTheRequestTrace() throws Exception {
    try (Recorder r = new Recorder((String) null)) {
      AccessProbe.Outcome result =
          probe()
              .query(
                  "127.0.0.1",
                  r.port(),
                  r.dataPort(),
                  AccessProbe.Mode.LEGACY_SESSION,
                  "C9K1_V6_580",
                  100);
      assertNotNull(result);
      assertFalse(result.sessionOpened);
      assertTrue(result.trace.contains("257"));
      assertFalse(result.summary.isEmpty());
    }
  }

  @Test
  public void cancellingTheReadDoesNotSendMoreCommands() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Recorder r = new Recorder((String) null);
        AccessProbe p = probe()) {
      Future<AccessProbe.Outcome> future =
          worker.submit(
              () ->
                  p.query(
                      "127.0.0.1",
                      r.port(),
                      r.dataPort(),
                      AccessProbe.Mode.LEGACY_SESSION,
                      "C9K1_V6_580",
                      3000));
      if (future.isDone()) assertNotNull("Missing implementation", future.get());
      r.first.get(1, TimeUnit.SECONDS);
      p.close();
      assertNotNull(future.get(1, TimeUnit.SECONDS));
      assertEquals(1, r.done.get(1, TimeUnit.SECONDS).size());
    } finally {
      worker.shutdownNow();
    }
  }

  private AccessProbe probe() {
    return new AccessProbe(Socket::new, s -> {});
  }

  private static final class Recorder implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0), data = new ServerSocket(0);
    final ExecutorService worker = Executors.newFixedThreadPool(2);
    final CompletableFuture<JSONObject> first = new CompletableFuture<>();
    final CompletableFuture<Void> firstReplyChunk = new CompletableFuture<>();
    final CompletableFuture<List<JSONObject>> done = new CompletableFuture<>();
    volatile Socket socket, dataSocket;

    Recorder(String... replies) throws IOException {
      this(0, replies);
    }

    Recorder(long fragmentDelayMs, String... replies) throws IOException {
      this(fragmentDelayMs, 3, replies);
    }

    Recorder(long fragmentDelayMs, int fragmentSize, String... replies) throws IOException {
      worker.submit(
          () -> {
            List<JSONObject> commands = new ArrayList<>();
            try (Socket s = server.accept()) {
              socket = s;
              s.setSoTimeout(1600);
              JsonObjectReader input =
                  new JsonObjectReader(
                      new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 65536);
              for (String reply : replies) {
                JSONObject command = new JSONObject(input.next());
                commands.add(command);
                first.complete(command);
                if (reply == null) {
                  while (s.getInputStream().read() != -1) {}
                  break;
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < bytes.length; i += fragmentSize) {
                  s.getOutputStream().write(bytes, i, Math.min(fragmentSize, bytes.length - i));
                  s.getOutputStream().flush();
                  firstReplyChunk.complete(null);
                  if (i + fragmentSize < bytes.length && fragmentDelayMs > 0)
                    Thread.sleep(fragmentDelayMs);
                }
              }
              try {
                commands.add(new JSONObject(input.next()));
              } catch (EOFException expected) {
              }
              done.complete(commands);
            } catch (Exception error) {
              done.completeExceptionally(error);
              first.completeExceptionally(error);
            }
          });
      worker.submit(
          () -> {
            try (Socket s = data.accept()) {
              dataSocket = s;
              while (s.getInputStream().read() != -1) {}
            } catch (IOException ignored) {
            }
          });
    }

    int port() {
      return server.getLocalPort();
    }

    int dataPort() {
      return data.getLocalPort();
    }

    public void close() throws IOException {
      server.close();
      data.close();
      if (socket != null) socket.close();
      if (dataSocket != null) dataSocket.close();
      worker.shutdownNow();
    }
  }
}
