package io.github.ajdailey.qcaret.app.ipc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A non-blocking loopback socket read and written as ASCII lines, every operation bounded by one
 * deadline. A line longer than {@link Protocol#MAX_LINE} bytes, a byte outside printable ASCII,
 * more than {@code maxBytes} read in all, end of stream or the deadline ends the exchange with an
 * {@link IOException}. Used by one thread.
 */
final class LineChannel implements AutoCloseable {
  private final SocketChannel channel;
  private final Selector selector;
  private final long deadline;
  private final long maxBytes;
  private final ByteBuffer in = ByteBuffer.allocate(4096);
  private long read;

  private LineChannel(SocketChannel channel, long deadline, long maxBytes) throws IOException {
    this.channel = channel;
    this.deadline = deadline;
    this.maxBytes = maxBytes;
    in.flip();
    boolean opened = false;
    Selector created = null;
    try {
      channel.configureBlocking(false);
      created = Selector.open();
      opened = true;
    } finally {
      if (!opened) {
        channel.close();
      }
    }
    this.selector = created;
  }

  /**
   * Connects to a loopback port.
   *
   * @param port the port
   * @param connectBound the longest connect
   * @param deadline {@link System#nanoTime} at which every later operation fails
   * @return the connected channel
   * @throws IOException when the connect fails or times out
   */
  static LineChannel connect(int port, Duration connectBound, long deadline) throws IOException {
    LineChannel line = new LineChannel(SocketChannel.open(), deadline, Protocol.MAX_REQUEST);
    boolean connected = false;
    try {
      long connectBy = Math.min(deadline, System.nanoTime() + connectBound.toNanos());
      if (!line.channel.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
        line.await(SelectionKey.OP_CONNECT, connectBy);
        line.channel.finishConnect();
      }
      connected = true;
      return line;
    } finally {
      if (!connected) {
        line.close();
      }
    }
  }

  /**
   * Wraps an accepted connection.
   *
   * @param channel the connection
   * @param deadline {@link System#nanoTime} at which every operation fails
   * @return the line channel
   * @throws IOException when the channel cannot be made non-blocking
   */
  static LineChannel accepted(SocketChannel channel, long deadline) throws IOException {
    return new LineChannel(channel, deadline, Protocol.MAX_REQUEST);
  }

  /**
   * Reads one line without its LF.
   *
   * @return the line
   * @throws IOException on a protocol violation, end of stream or the deadline
   */
  String readLine() throws IOException {
    StringBuilder line = new StringBuilder();
    while (true) {
      while (in.hasRemaining()) {
        byte b = in.get();
        if (b == '\n') {
          return line.toString();
        }
        if (b < 0x20 || b > 0x7e) {
          throw new IOException("Not a printable ASCII line");
        }
        if (line.length() == Protocol.MAX_LINE) {
          throw new IOException("Line too long");
        }
        line.append((char) b);
      }
      in.clear();
      int count = channel.read(in);
      while (count == 0) {
        await(SelectionKey.OP_READ, deadline);
        count = channel.read(in);
      }
      in.flip();
      if (count < 0) {
        throw new IOException("Connection closed");
      }
      read += count;
      if (read > maxBytes) {
        throw new IOException("Request too large");
      }
    }
  }

  /**
   * Writes one line and its LF.
   *
   * @param line printable ASCII
   * @throws IOException when the write fails or the deadline passes
   */
  void writeLine(String line) throws IOException {
    ByteBuffer out = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.US_ASCII));
    while (out.hasRemaining()) {
      if (channel.write(out) == 0) {
        await(SelectionKey.OP_WRITE, deadline);
      }
    }
  }

  private void await(int operation, long by) throws IOException {
    SelectionKey key = channel.register(selector, operation);
    try {
      while (true) {
        long left = by - System.nanoTime();
        if (left <= 0) {
          throw new IOException("Timed out");
        }
        if (selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))) > 0) {
          selector.selectedKeys().clear();
          return;
        }
      }
    } finally {
      key.cancel();
      selector.selectNow();
    }
  }

  @Override
  public void close() throws IOException {
    try {
      selector.close();
    } finally {
      channel.close();
    }
  }
}
