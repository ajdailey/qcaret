package io.github.ajdailey.qcaret.app.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The handoff server's bounds on one peer: line length, printable ASCII, request bytes, {@code
 * OPEN} lines and connection slots. A line or byte bound ends the connection with no reply, which
 * tells it from the {@code ERR} of a malformed request. Each case then hands off one more launch.
 */
class HandoffLimitsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<StartupRequest> received = new CopyOnWriteArrayList<StartupRequest>();
  private Workers workers;
  private InstanceServer server;

  @BeforeEach
  void setUp() throws IOException {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    server = InstanceServer.claim(temp);
    assertNotNull(server);
    assertTrue(server.start(workers));
    server.deliverTo(
        new InstanceServer.Receiver() {
          @Override
          public boolean deliver(StartupRequest request) {
            received.add(request);
            return true;
          }
        });
  }

  @AfterEach
  void tearDown() {
    server.close();
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** A raw loopback peer that writes any bytes and reads the server's lines. */
  private final class Peer implements AutoCloseable {
    private final Socket socket = new Socket();
    private final Endpoint endpoint;
    private final InputStream in;

    Peer() throws IOException {
      endpoint = Endpoint.read(temp);
      socket.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()),
          (int) BOUND.toMillis());
      socket.setSoTimeout((int) BOUND.toMillis());
      in = socket.getInputStream();
    }

    /** Sends HELLO and AUTH with the endpoint's token. */
    Peer authenticate() throws IOException {
      byte[] nonceC = Protocol.secret();
      send(Protocol.HELLO + Protocol.encode(nonceC));
      String proof = readLine();
      assertNotNull(proof);
      byte[] nonceS = Protocol.decode(proof.substring(Protocol.PROOF.length()).split(" ")[0]);
      send(
          Protocol.AUTH
              + Protocol.encode(Protocol.proof(endpoint.token(), "client", nonceS, nonceC)));
      return this;
    }

    void send(String line) throws IOException {
      send((line + "\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Writes bytes; a write failing because the server closed is ignored. */
    void send(byte[] bytes) throws IOException {
      try {
        socket.getOutputStream().write(bytes);
      } catch (SocketTimeoutException e) {
        throw e;
      } catch (IOException e) {
        // The server closed the connection; readLine reports it.
      }
    }

    /**
     * Reads one line.
     *
     * @return the line; null when the server closed or reset the connection
     * @throws SocketTimeoutException when the server neither replied nor closed within the bound
     */
    String readLine() throws IOException {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      try {
        int b = in.read();
        while (b != '\n') {
          if (b < 0) {
            return null;
          }
          line.write(b);
          b = in.read();
        }
      } catch (SocketTimeoutException e) {
        throw e;
      } catch (IOException e) {
        return null;
      }
      return line.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  /** A WINDOW line that names nothing. */
  private static String window() {
    return Protocol.WINDOW + " 0 - - - -";
  }

  /** An OPEN line for {@code name}, padded to {@code length} by the line field; 0 pads nothing. */
  private String open(String name, int length) {
    String path =
        Protocol.encode(
            temp.resolve(name).toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    String head = Protocol.OPEN + path + " ";
    String tail = " 0 0 -";
    int digits = Math.max(1, length - head.length() - tail.length());
    String line = head + "0".repeat(digits) + tail;
    assertTrue(length == 0 || line.length() == length, line.length() + " for " + length);
    return line;
  }

  private String open(String name) {
    return open(name, 0);
  }

  /** The server closes without a reply and takes nothing; the next launch is taken. */
  private void assertDroppedThenServed(Peer peer) throws IOException {
    assertNull(peer.readLine());
    assertTrue(received.isEmpty(), received.toString());
    assertServesTheNextLaunch();
  }

  private void assertServesTheNextLaunch() throws IOException {
    StartupRequest next =
        StartupRequest.handoff(
            List.of(new FileRequest(temp.resolve("next.txt").toAbsolutePath())),
            false,
            null,
            null,
            null,
            null,
            null,
            null);
    InstanceClient.handOff(temp, next);
    assertEquals(List.of(next), received);
  }

  @Test
  void aLineAtMaxLineIsTakenAndOneByteMoreIsDropped() throws Exception {
    try (Peer peer = new Peer().authenticate()) {
      peer.send(window());
      peer.send(open("long.txt", Protocol.MAX_LINE));
      peer.send(Protocol.END);
      assertEquals(Protocol.OK, peer.readLine());
    }
    assertEquals(1, received.size());
    received.clear();
    try (Peer peer = new Peer().authenticate()) {
      peer.send(window());
      peer.send(open("long.txt", Protocol.MAX_LINE + 1));
      peer.send(Protocol.END);
      assertDroppedThenServed(peer);
    }
  }

  @Test
  void aByteOutsidePrintableAsciiIsDropped() throws Exception {
    try (Peer peer = new Peer().authenticate()) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.writeBytes(Protocol.WINDOW.getBytes(StandardCharsets.US_ASCII));
      bytes.writeBytes(new byte[] {' ', '0', ' ', (byte) 0xd9, (byte) 0xa1, ' ', '-', ' ', '-'});
      bytes.writeBytes(" -\n".getBytes(StandardCharsets.US_ASCII));
      peer.send(bytes.toByteArray());
      peer.send(Protocol.END);
      assertDroppedThenServed(peer);
    }
  }

  @Test
  void aRequestOverMaxRequestIsDropped() throws Exception {
    int lines = Protocol.MAX_OPEN;
    assertTrue((long) lines * Protocol.MAX_LINE > Protocol.MAX_REQUEST);
    try (Peer peer = new Peer().authenticate()) {
      peer.send(window());
      for (int i = 0; i < lines; i++) {
        peer.send(open("f" + i + ".txt", Protocol.MAX_LINE));
      }
      peer.send(Protocol.END);
      assertDroppedThenServed(peer);
    }
  }

  @Test
  void maxOpenLinesAreTakenAndOneMoreIsRefused() throws Exception {
    try (Peer peer = new Peer().authenticate()) {
      peer.send(window());
      for (int i = 0; i < Protocol.MAX_OPEN; i++) {
        peer.send(open("f" + i + ".txt"));
      }
      peer.send(Protocol.END);
      assertEquals(Protocol.OK, peer.readLine());
    }
    assertEquals(Protocol.MAX_OPEN, received.get(0).files().size());
    received.clear();
    try (Peer peer = new Peer().authenticate()) {
      peer.send(window());
      for (int i = 0; i <= Protocol.MAX_OPEN; i++) {
        peer.send(open("f" + i + ".txt"));
      }
      peer.send(Protocol.END);
      assertEquals(Protocol.ERR, peer.readLine());
    }
    assertTrue(received.isEmpty(), received.toString());
    assertServesTheNextLaunch();
  }

  /**
   * Silent peers hold every slot until {@link InstanceServer#EXCHANGE_BOUND}: a launch meanwhile
   * gets no proof, and once the server drops them a launch is taken.
   */
  @Test
  void silentPeersHoldingEverySlotAreDroppedAtTheExchangeBound() throws Exception {
    List<Peer> peers = new ArrayList<Peer>();
    try {
      for (int i = 0; i < InstanceServer.MAX_CONNECTIONS; i++) {
        Peer peer = new Peer();
        peers.add(peer);
        peer.send(Protocol.HELLO + Protocol.encode(Protocol.secret()));
        assertTrue(peer.readLine().startsWith(Protocol.PROOF));
      }
      IOException waited =
          assertThrows(
              IOException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  InstanceClient.handOff(
                      temp,
                      StartupRequest.handoff(List.of(), false, null, null, null, null, null, null),
                      Duration.ofSeconds(1));
                }
              });
      assertEquals("Timed out", waited.getMessage());
      for (Peer peer : peers) {
        assertNull(peer.readLine());
      }
      assertTrue(received.isEmpty(), received.toString());
      assertServesTheNextLaunch();
    } finally {
      for (Peer peer : peers) {
        peer.close();
      }
    }
  }
}
