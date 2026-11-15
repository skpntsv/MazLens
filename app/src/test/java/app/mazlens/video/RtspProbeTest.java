package app.mazlens.video;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

/** Real loopback conversations: no recorder, emulator networking, or mocked sockets. */
public class RtspProbeTest {
  private static final String SDP =
      "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Loopback video\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n";

  @Test
  public void fragmentedRepliesProduceDescriptionAndAdvertisedVideoOnOneSocket() throws Exception {
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.fragment(reply(200, 1, "Public: OPTIONS, DESCRIBE\r\n", ""), 1);
                  s.readRequest();
                  s.fragment(
                      reply(200, 2, "Content-Type: application/sdp; charset=UTF-8\r\n", SDP), 3);
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 2000);
      assertTrue(result.serverResponded);
      assertTrue(result.descriptionReceived);
      assertTrue(result.videoAdvertised);
      assertFalse(result.authRequired);
      assertFalse(result.cancelled);
      assertTrue(result.response.contains(SDP));
      server.await();
      assertEquals(2, server.requests.size());
      String url = "rtsp://127.0.0.1:" + server.port() + "/live";
      assertTrue(server.requests.get(0).startsWith("OPTIONS " + url + " RTSP/1.0\r\n"));
      assertTrue(server.requests.get(0).contains("\r\nCSeq: 1\r\n"));
      assertTrue(server.requests.get(1).startsWith("DESCRIBE " + url + " RTSP/1.0\r\n"));
      assertTrue(server.requests.get(1).contains("\r\nCSeq: 2\r\n"));
      assertTrue(server.requests.get(1).contains("\r\nAccept: application/sdp\r\n"));
      assertFalse(String.join("", server.requests).contains("Authorization:"));
      assertFalse(String.join("", server.requests).contains("SETUP "));
      assertFalse(String.join("", server.requests).contains("PLAY "));
    }
  }

  @Test
  public void unauthorizedOptionsStopsWithoutDescribeOrCredentialRetryAndKeepsPrivateReply()
      throws Exception {
    List<String> log = new ArrayList<>();
    String challenge =
        "WWW-Authenticate: Digest realm=\"private-password-marker\", nonce=\"private-nonce\"\r\n";
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send(reply(401, 1, challenge, ""));
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, log::add)) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.serverResponded);
      assertTrue(result.authRequired);
      assertFalse(result.descriptionReceived);
      assertFalse(result.videoAdvertised);
      assertTrue(result.response.contains("private-password-marker"));
      assertFalse(String.join("\n", log).contains("private-password-marker"));
      assertFalse(String.join("\n", log).contains("private-nonce"));
      server.await();
      assertEquals(1, server.requests.size());
    }
  }

  @Test
  public void missingLivePathIsAResponseWithoutDescriptionOrVideo() throws Exception {
    try (Server server =
            afterOptions(200, reply(404, 2, "Content-Type: application/sdp\r\n", SDP));
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertFalse(result.videoAdvertised);
      assertFalse(result.authRequired);
      server.await();
    }
  }

  @Test
  public void silentTcpConnectionIsNotAServerReplyOrVideo() throws Exception {
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      long start = System.nanoTime();
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 150);
      assertTrue(result.timedOut);
      assertFalse(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertFalse(result.videoAdvertised);
      assertFalse(result.cancelled);
      assertEquals("", result.response);
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
      server.await();
    }
  }

  @Test
  public void timeoutRetainsPartialHeaderAndUsesOneOverallDeadlineDespiteDribbling()
      throws Exception {
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send("RTSP/1.0 200 OK\r\nCSeq: 1\r\nX-Partial: ");
                  while (true) {
                    s.send("x");
                    Thread.sleep(20);
                  }
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      long start = System.nanoTime();
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 180);
      assertFalse(result.cancelled);
      assertFalse(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertTrue(result.response.contains("X-Partial: "));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
    }
  }

  private static String reply(int status, int cseq, String headers, String body) {
    return "RTSP/1.0 "
        + status
        + " Fixture\r\nCSeq: "
        + cseq
        + "\r\n"
        + headers
        + "Content-Length: "
        + body.getBytes(StandardCharsets.UTF_8).length
        + "\r\n\r\n"
        + body;
  }

  private static Server afterOptions(int optionsStatus, String description) throws IOException {
    return new Server(
        s -> {
          s.readRequest();
          s.send(reply(optionsStatus, 1, "", ""));
          s.readRequest();
          s.send(description);
          s.expectClientClosed();
        });
  }

  private interface Script {
    void run(Server server) throws Exception;
  }

  private static final class Server implements AutoCloseable {
    final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    final Future<?> task;
    volatile Socket client;

    Server(Script script) throws IOException {
      task =
          worker.submit(
              () -> {
                try (Socket accepted = listener.accept()) {
                  client = accepted;
                  accepted.setSoTimeout(3000);
                  script.run(this);
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              });
    }

    int port() {
      return listener.getLocalPort();
    }

    void readRequest() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      int next;
      while ((next = client.getInputStream().read()) != -1) {
        bytes.write(next);
        byte[] value = bytes.toByteArray();
        if (value.length >= 4
            && value[value.length - 4] == '\r'
            && value[value.length - 3] == '\n'
            && value[value.length - 2] == '\r'
            && value[value.length - 1] == '\n') {
          requests.add(new String(value, StandardCharsets.US_ASCII));
          return;
        }
        if (bytes.size() > 4096) throw new IOException("Oversized client request");
      }
      throw new EOFException("Expected request");
    }

    void send(String value) throws IOException {
      client.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();
    }

    void fragment(String value, int fragmentSize) throws IOException {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      for (int i = 0; i < bytes.length; i += fragmentSize) {
        client.getOutputStream().write(bytes, i, Math.min(fragmentSize, bytes.length - i));
        client.getOutputStream().flush();
      }
    }

    void expectClientClosed() throws IOException {
      try {
        assertEquals(
            "Probe must close without any extra request", -1, client.getInputStream().read());
      } catch (SocketException resetAfterBoundedRead) {
        /* Closing with unread oversized input may send RST. */
      }
    }

    void await() throws Exception {
      task.get(3, TimeUnit.SECONDS);
    }

    public void close() throws IOException {
      listener.close();
      if (client != null) client.close();
      worker.shutdownNow();
    }
  }
}
