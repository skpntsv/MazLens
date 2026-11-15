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
  public void unauthorizedDescribeStopsWithoutRetry() throws Exception {
    try (Server server =
            afterOptions(200, reply(401, 2, "WWW-Authenticate: Basic realm=\"private\"\r\n", ""));
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.serverResponded);
      assertTrue(result.authRequired);
      assertFalse(result.descriptionReceived);
      server.await();
      assertEquals(2, server.requests.size());
    }
  }

  @Test
  public void unsupportedOptionsStillAllowsDescribe() throws Exception {
    for (int code : new int[] {405, 501}) {
      try (Server server =
              afterOptions(code, reply(200, 2, "Content-Type: application/sdp\r\n", SDP));
          RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
        assertTrue(result.descriptionReceived);
        assertTrue(result.videoAdvertised);
        server.await();
      }
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
  public void optionsErrorsOtherThanUnsupportedStopBeforeDescribe() throws Exception {
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send(reply(404, 1, "", ""));
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      server.await();
      assertEquals(1, server.requests.size());
    }
  }

  @Test
  public void successfulOptionsAloneNeverCountsAsVideo() throws Exception {
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send(reply(200, 1, "Content-Type: application/sdp\r\n", SDP));
                  s.readRequest();
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 150);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertFalse(result.videoAdvertised);
      assertFalse(result.cancelled);
      server.await();
    }
  }

  @Test
  public void absentWrongOrFakeSdpIsNeverAUsableDescription() throws Exception {
    String[] descriptions = {
      reply(200, 2, "", SDP),
      reply(200, 2, "Content-Type: text/plain\r\n", SDP),
      reply(200, 2, "Content-Type: application/sdp\r\n", ""),
      reply(200, 2, "Content-Type: application/sdp\r\n", "m=video 0 RTP/AVP 96\r\n"),
      reply(
          200,
          2,
          "Content-Type: application/sdp\r\n",
          "v=0\r\ns=private\r\nm=video 0 RTP/AVP 96\r\n"),
      reply(200, 2, "Content-Type: application/sdp\r\n", "<html>v=0 m=video</html>")
    };
    for (String description : descriptions) {
      try (Server server = afterOptions(200, description);
          RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
        assertTrue(result.serverResponded);
        assertFalse(result.descriptionReceived);
        assertFalse(result.videoAdvertised);
        server.await();
      }
    }
  }

  @Test
  public void audioOnlySdpIsDescriptionWithoutVideo() throws Exception {
    String audio = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Audio\r\nt=0 0\r\nm=audio 0 RTP/AVP 0\r\n";
    try (Server server =
            afterOptions(200, reply(200, 2, "Content-Type: APPLICATION/SDP\r\n", audio));
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.descriptionReceived);
      assertFalse(result.videoAdvertised);
      server.await();
    }
  }

  @Test
  public void malformedMediaTransportPayloadAndCodecMappingsAreNotDescriptions() throws Exception {
    String base = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Malformed media\r\nt=0 0\r\n";
    String[] media = {
      "m=video 0 ?? ??\r\n",
      "m=video 0 RTP/AVP not-a-payload\r\n",
      "m=video 0 RTP/AVP 128\r\n",
      "m=video 0 RTP/AVP 96 96\r\n",
      "m=video 0 RTP/AVP 96\r\na=rtpmap:96 ??/90000\r\n",
      "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/0\r\n",
      "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=rtpmap:96 H265/90000\r\n"
    };
    for (String value : media) {
      try (Server server =
              afterOptions(200, reply(200, 2, "Content-Type: application/sdp\r\n", base + value));
          RtspProbe probe = new RtspProbe(Socket::new, ignored -> {})) {
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
        assertTrue(result.serverResponded);
        assertFalse(
            "Malformed media must not be a description: " + value, result.descriptionReceived);
        assertFalse(result.videoAdvertised);
        server.await();
      }
    }
  }

  @Test
  public void unmappedDynamicPayloadIsDescriptionWithoutUsableVideo() throws Exception {
    String base = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Unmapped media\r\nt=0 0\r\n";
    for (String media :
        new String[] {
          "m=video 0 RTP/AVP 96\r\n",
          "m=video 0 RTP/AVP 96\r\na=rtpmap:97 H264/90000\r\n",
          "m=video 0 RTP/AVP 96\r\nm=audio 0 RTP/AVP 96\r\na=rtpmap:96 PCMU/8000\r\n"
        }) {
      try (Server server =
              afterOptions(200, reply(200, 2, "Content-Type: application/sdp\r\n", base + media));
          RtspProbe probe = new RtspProbe(Socket::new, ignored -> {})) {
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
        assertTrue(result.descriptionReceived);
        assertFalse(
            "Missing media-specific mapping must not enable playback", result.videoAdvertised);
        server.await();
      }
    }
  }

  @Test
  public void staticVideoPayloadAndMappedDynamicPortZeroRemainAdvertised() throws Exception {
    String base = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Valid media\r\nt=0 0\r\n";
    for (String media :
        new String[] {
          "m=video 0 RTP/AVP 26\r\n", "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
        }) {
      try (Server server =
              afterOptions(200, reply(200, 2, "Content-Type: application/sdp\r\n", base + media));
          RtspProbe probe = new RtspProbe(Socket::new, ignored -> {})) {
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
        assertTrue(result.descriptionReceived);
        assertTrue("RTSP port zero is a valid transport placeholder", result.videoAdvertised);
        server.await();
      }
    }
  }

  @Test
  public void malformedOrUnmatchedCseqDoesNotBecomeValidRtspResponse() throws Exception {
    for (String response :
        new String[] {
          "HTTP/1.1 200 OK\r\nCSeq: 1\r\n\r\n",
          "RTSP/1.0 garbage\r\nCSeq: 1\r\n\r\n",
          "RTSP/1.0 200 OK\r\n\r\n",
          "RTSP/1.0 200 OK\r\nCSeq: private-secret\r\n\r\n",
          "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n",
          "RTSP/1.0 200 OK\r\nCSeq: 1\r\nCSeq: 1\r\n\r\n"
        }) {
      List<String> log = new ArrayList<>();
      try (Server server =
              new Server(
                  s -> {
                    s.readRequest();
                    s.send(response);
                    s.expectClientClosed();
                  });
          RtspProbe probe = new RtspProbe(Socket::new, log::add)) {
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
        assertFalse(result.serverResponded);
        assertFalse(result.descriptionReceived);
        assertFalse(result.videoAdvertised);
        assertTrue(result.response.contains(response));
        assertFalse(String.join("", log).contains("private-secret"));
        server.await();
        assertEquals(1, server.requests.size());
      }
    }
  }

  @Test
  public void wrongDescribeCseqNeverCountsAsDescription() throws Exception {
    try (Server server =
            afterOptions(200, reply(200, 77, "Content-Type: application/sdp\r\n", SDP));
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertFalse(result.videoAdvertised);
      assertTrue(result.response.contains("CSeq: 77"));
      server.await();
    }
  }

  @Test
  public void rejectsInvalidDuplicateAndOversizedContentLengthsWithoutWaitingForBody()
      throws Exception {
    for (String length :
        new String[] {
          "-1", "private-size", "99999999999999999999999", "131073", "1\r\nContent-Length: 1"
        }) {
      String response =
          "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: "
              + length
              + "\r\n\r\n";
      try (Server server = afterOptions(200, response);
          RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
        long start = System.nanoTime();
        RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 2000);
        assertFalse(result.descriptionReceived);
        assertFalse(result.videoAdvertised);
        assertTrue(result.response.contains("Content-Length:"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
        server.await();
      }
    }
  }

  @Test
  public void aggregateBudgetIncludesOptionsAndDescribeTogether() throws Exception {
    String padding = "x".repeat(70000);
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send(reply(200, 1, "", padding));
                  s.readRequest();
                  s.send(reply(200, 2, "Content-Type: application/sdp\r\n", padding));
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 2000);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertTrue(result.response.length() <= 131072);
      server.await();
    }
  }

  @Test
  public void headerBudgetStopsAnUnterminatedOversizedHeader() throws Exception {
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send("RTSP/1.0 200 OK\r\nCSeq: 1\r\nX-Secret: " + "x".repeat(40000));
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      long start = System.nanoTime();
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 2000);
      assertFalse(result.descriptionReceived);
      assertTrue(result.response.length() <= 32768);
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
      server.await();
    }
  }

  @Test
  public void truncatedBodyRetainsPartialReplyWithoutDescription() throws Exception {
    String partial =
        "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: 99\r\n\r\nv=0\r\nprivate-partial";
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send(reply(200, 1, "", ""));
                  s.readRequest();
                  s.send(partial);
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      RtspProbe.Outcome result = probe.query("127.0.0.1", server.port(), 1000);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertTrue(result.response.endsWith("private-partial"));
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

  @Test
  public void cancelClosesBlockedReadAndRetainsTheEarlierCompleteReply() throws Exception {
    CountDownLatch describe = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Server server =
            new Server(
                s -> {
                  s.readRequest();
                  s.send(reply(200, 1, "X-Private: retained\r\n", ""));
                  s.readRequest();
                  describe.countDown();
                  s.expectClientClosed();
                });
        RtspProbe probe = new RtspProbe(Socket::new, value -> {})) {
      Future<RtspProbe.Outcome> pending =
          executor.submit(() -> probe.query("127.0.0.1", server.port(), 5000));
      assertTrue(describe.await(2, TimeUnit.SECONDS));
      probe.close();
      RtspProbe.Outcome result = pending.get(1, TimeUnit.SECONDS);
      assertTrue(result.cancelled);
      assertTrue(result.serverResponded);
      assertFalse(result.descriptionReceived);
      assertTrue(result.response.contains("X-Private: retained"));
      server.await();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void alreadyCancelledProbeNeverOpensASocket() throws Exception {
    java.util.concurrent.atomic.AtomicInteger opened =
        new java.util.concurrent.atomic.AtomicInteger();
    RtspProbe probe =
        new RtspProbe(
            () -> {
              opened.incrementAndGet();
              return new Socket();
            },
            value -> {});
    probe.close();
    RtspProbe.Outcome result = probe.query("127.0.0.1", 554, 1000);
    assertTrue(result.cancelled);
    assertFalse(result.serverResponded);
    assertEquals(0, opened.get());
  }

  @Test
  public void requestInjectionIsRejectedBeforeOpeningASocket() throws Exception {
    for (String host :
        new String[] {
          "127.0.0.1\r\nAuthorization: secret",
          "user:pass@127.0.0.1",
          "127.0.0.1/path",
          "rtsp://127.0.0.1",
          "127.0.0.1?x=y"
        }) {
      java.util.concurrent.atomic.AtomicInteger opened =
          new java.util.concurrent.atomic.AtomicInteger();
      try (RtspProbe probe =
          new RtspProbe(
              () -> {
                opened.incrementAndGet();
                return new Socket();
              },
              value -> {})) {
        RtspProbe.Outcome result = probe.query(host, 554, 1000);
        assertFalse(result.descriptionReceived);
        assertFalse(result.serverResponded);
        assertEquals(0, opened.get());
      }
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
