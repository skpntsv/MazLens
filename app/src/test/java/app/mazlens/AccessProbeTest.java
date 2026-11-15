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
  public void modelOnlySessionRecoversPasswordAndReleasesItsToken() throws Exception {
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
                  AccessProbe.Mode.MODEL_ONLY,
                  "C9K1_V6_580",
                  700);
      assertNotNull(result);
      assertTrue(result.summary + "\n" + result.trace, result.sessionOpened);
      assertEquals("owner-secret", result.password);
      List<JSONObject> commands = r.done.get(2, TimeUnit.SECONDS);
      assertEquals(3, commands.size());
      assertEquals(257, commands.get(0).getInt("msg_id"));
      assertEquals(0, commands.get(0).getInt("token"));
      assertEquals("C9K1_V6_580", commands.get(0).getString("model_name"));
      assertFalse(commands.get(0).has("password"));
      assertEquals(3, commands.get(1).getInt("msg_id"));
      assertEquals(23, commands.get(1).getInt("token"));
      assertEquals(258, commands.get(2).getInt("msg_id"));
      assertEquals(23, commands.get(2).getInt("token"));
      assertTrue(result.trace.contains("owner-secret"));
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
    final CompletableFuture<List<JSONObject>> done = new CompletableFuture<>();
    volatile Socket socket, dataSocket;

    Recorder(String... replies) throws IOException {
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
                s.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
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
