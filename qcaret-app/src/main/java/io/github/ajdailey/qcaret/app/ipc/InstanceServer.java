package io.github.ajdailey.qcaret.app.ipc;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The primary instance's side of the single-instance handoff. {@link #claim} takes the OS lock on
 * {@code instance.lock}, which the OS releases when the process dies, so a stale {@code
 * instance.endpoint} never decides liveness: the holder deletes any endpoint file, binds an
 * ephemeral port on the loopback address and writes the new endpoint with a fresh token.
 *
 * <p>{@link #start} accepts on a {@code qcaret-ipc-} thread and serves each connection on its own,
 * at most {@link #MAX_CONNECTIONS} at once (more are closed on accept), each within {@link
 * #EXCHANGE_BOUND}. A peer that fails authentication gets no reply and no file is opened. Each path
 * must be absolute; the {@link Receiver} opens it like a command-line path. {@link #close} stops
 * accepting, deletes the endpoint, then releases the lock, in that order, so a new primary never
 * loses its own endpoint file. Close it before {@link Workers#close}, which waits for the accept
 * thread.
 */
public final class InstanceServer implements AutoCloseable {
  /** Receives a handoff. */
  public interface Receiver {
    /**
     * Takes a launch; called on a {@code qcaret-ipc-} thread.
     *
     * @param request the files with absolute paths and their options, {@code -n}, the geometry and
     *     {@code --diff}
     * @return false when the instance is quitting and takes nothing
     */
    boolean deliver(StartupRequest request);
  }

  /** Most connections served at once. */
  public static final int MAX_CONNECTIONS = 4;

  /** Longest exchange with one client. */
  public static final Duration EXCHANGE_BOUND = Duration.ofSeconds(5);

  private static final Logger LOG = LoggerFactory.getLogger(InstanceServer.class);

  private final Path directory;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final ServerSocketChannel server;
  private final byte[] token;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicInteger connections = new AtomicInteger();

  private InstanceServer(
      Path directory,
      FileChannel lockChannel,
      FileLock lock,
      ServerSocketChannel server,
      byte[] token) {
    this.directory = directory;
    this.lockChannel = lockChannel;
    this.lock = lock;
    this.server = server;
    this.token = token;
  }

  /**
   * Becomes the primary instance for a settings directory when no live instance is.
   *
   * @param directory the settings directory
   * @return the server, bound, its endpoint written; null when a live instance holds the lock
   * @throws IOException when the lock file, the port or the endpoint file fails
   */
  public static InstanceServer claim(Path directory) throws IOException {
    FileChannel channel =
        FileChannel.open(
            SafePaths.child(directory, Endpoint.LOCK),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    InstanceServer claimed = null;
    try {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null;
      }
      if (lock == null) {
        return null;
      }
      ServerSocketChannel server = null;
      try {
        Endpoint.delete(directory);
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), MAX_CONNECTIONS);
        int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        byte[] token = Protocol.secret();
        new Endpoint(port, token).write(directory);
        claimed = new InstanceServer(directory, channel, lock, server, token);
        return claimed;
      } finally {
        if (claimed == null && server != null) {
          server.close();
        }
      }
    } finally {
      if (claimed == null) {
        channel.close();
      }
    }
  }

  /**
   * Starts accepting handoffs.
   *
   * @param workers starts the {@code qcaret-ipc-} threads
   * @param receiver takes the files
   * @return false when the workers are closed
   */
  public boolean start(final Workers workers, final Receiver receiver) {
    return workers.tryStart(
        Workers.Kind.IPC,
        new Runnable() {
          @Override
          public void run() {
            accept(workers, receiver);
          }
        });
  }

  private void accept(Workers workers, final Receiver receiver) {
    while (!closed.get()) {
      final SocketChannel connection;
      try {
        connection = server.accept();
      } catch (ClosedChannelException e) {
        return;
      } catch (IOException e) {
        LOG.warn("Handoff listener stopped: {}", LogText.oneLine(e.getMessage()));
        return;
      }
      if (connections.incrementAndGet() > MAX_CONNECTIONS
          || !workers.tryStart(
              Workers.Kind.IPC,
              new Runnable() {
                @Override
                public void run() {
                  try {
                    serve(connection, receiver);
                  } finally {
                    connections.decrementAndGet();
                  }
                }
              })) {
        connections.decrementAndGet();
        closeQuietly(connection);
      }
    }
  }

  private void serve(SocketChannel connection, Receiver receiver) {
    long deadline = System.nanoTime() + EXCHANGE_BOUND.toNanos();
    try (LineChannel line = LineChannel.accepted(connection, deadline)) {
      if (!authenticate(line)) {
        LOG.warn("Handoff refused: the peer did not prove the token");
        return;
      }
      StartupRequest request = readRequest(line);
      boolean taken = request != null && receiver.deliver(request);
      line.writeLine(taken ? Protocol.OK : Protocol.ERR);
      if (request == null) {
        LOG.warn("Handoff refused: malformed request");
      } else if (LOG.isInfoEnabled()) {
        LOG.info("Handoff of {} files {}", request.files().size(), taken ? "taken" : "refused");
      }
    } catch (IOException e) {
      LOG.debug("Handoff connection ended: {}", LogText.oneLine(e.getMessage()));
    }
  }

  /** Runs HELLO, PROOF and AUTH; false when the peer's proof is wrong. */
  private boolean authenticate(LineChannel line) throws IOException {
    String hello = line.readLine();
    if (!hello.startsWith(Protocol.HELLO)) {
      return false;
    }
    byte[] nonceC = Protocol.decode(hello.substring(Protocol.HELLO.length()));
    if (nonceC.length != Protocol.SECRET_BYTES) {
      return false;
    }
    byte[] nonceS = Protocol.secret();
    line.writeLine(
        Protocol.PROOF
            + Protocol.encode(nonceS)
            + " "
            + Protocol.encode(Protocol.proof(token, "server", nonceC, nonceS)));
    String auth = line.readLine();
    return auth.startsWith(Protocol.AUTH)
        && Protocol.matches(
            Protocol.proof(token, "client", nonceS, nonceC),
            Protocol.decode(auth.substring(Protocol.AUTH.length())));
  }

  /**
   * Reads WINDOW, the OPEN lines, an optional DIFF and END.
   *
   * @return the request, or null when a line is malformed
   */
  private static StartupRequest readRequest(LineChannel line) throws IOException {
    String[] window = line.readLine().split(" ", -1);
    if (window.length != 6
        || !Protocol.WINDOW.equals(window[0])
        || !("0".equals(window[1]) || "1".equals(window[1]))) {
      return null;
    }
    try {
      Integer x = number(window[2]);
      Integer y = number(window[3]);
      Integer width = number(window[4]);
      Integer height = number(window[5]);
      List<FileRequest> files = new ArrayList<FileRequest>();
      Path[] diff = new Path[2];
      while (true) {
        String next = line.readLine();
        if (next.equals(Protocol.END)) {
          return StartupRequest.handoff(
              files, "1".equals(window[1]), x, y, width, height, diff[0], diff[1]);
        }
        boolean parsed;
        if (next.startsWith(Protocol.DIFF) && diff[0] == null) {
          parsed = parseDiff(next.substring(Protocol.DIFF.length()), diff);
        } else if (next.startsWith(Protocol.OPEN) && files.size() < Protocol.MAX_OPEN) {
          FileRequest file = parseOpen(next.substring(Protocol.OPEN.length()));
          parsed = file != null && files.add(file);
        } else {
          parsed = false;
        }
        if (!parsed) {
          return null;
        }
      }
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer number(String text) {
    return Protocol.NONE.equals(text) ? null : Integer.valueOf(text);
  }

  /** Parses {@code <left> <right>} into {@code diff}; false when malformed or not absolute. */
  private static boolean parseDiff(String text, Path[] diff) {
    String[] parts = text.split(" ", -1);
    if (parts.length != 2) {
      return false;
    }
    diff[0] = absolutePath(parts[0]);
    diff[1] = absolutePath(parts[1]);
    return diff[0] != null && diff[1] != null;
  }

  /**
   * Parses {@code <path> <line> <column> <readOnly> <encoding>}.
   *
   * @return the file; null when malformed, not absolute or in an encoding the runtime lacks
   */
  static FileRequest parseOpen(String text) {
    String[] parts = text.split(" ", -1);
    if (parts.length != 5 || !("0".equals(parts[3]) || "1".equals(parts[3]))) {
      return null;
    }
    Path path = absolutePath(parts[0]);
    boolean named = !Protocol.NONE.equals(parts[4]);
    String encoding = named ? decodeText(parts[4]) : null;
    try {
      int line = Integer.parseInt(parts[1]);
      int column = Integer.parseInt(parts[2]);
      if (path == null || line < 0 || column < 0 || (named && !supported(encoding))) {
        return null;
      }
      return new FileRequest(path, line, column, "1".equals(parts[3]), encoding);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean supported(String encoding) {
    try {
      return encoding != null && Charset.isSupported(encoding);
    } catch (IllegalCharsetNameException e) {
      return false;
    }
  }

  private static Path absolutePath(String encoded) {
    String name = decodeText(encoded);
    if (name == null || name.indexOf('\0') >= 0) {
      return null;
    }
    try {
      Path path = Path.of(name);
      return path.isAbsolute() ? path : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /** Decodes base64url UTF-8; null when malformed. */
  private static String decodeText(String encoded) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(Protocol.decode(encoded)))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  /**
   * Stops accepting, deletes the endpoint file and releases the lock; a second call does nothing.
   * Callable from any thread.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    closeQuietly(server);
    try {
      Endpoint.delete(directory);
    } catch (IOException e) {
      LOG.warn("Cannot delete the handoff endpoint: {}", LogText.oneLine(e.getMessage()));
    }
    try {
      lock.release();
    } catch (IOException e) {
      LOG.debug("Cannot release the instance lock: {}", LogText.oneLine(e.getMessage()));
    }
    closeQuietly(lockChannel);
  }

  private static void closeQuietly(Channel channel) {
    try {
      channel.close();
    } catch (IOException e) {
      LOG.debug("Close failed: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
