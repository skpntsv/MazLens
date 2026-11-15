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
