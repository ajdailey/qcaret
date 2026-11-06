package io.github.ajdailey.qcaret.app.ipc;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.Set;

/**
 * A launch's side of the single-instance handoff: reads {@code instance.endpoint}, connects to the
 * loopback port, proves the token through HMAC over fresh nonces after the primary has proved it,
 * and sends the files. Blocking; the launcher calls it before the Display exists. A client that
 * receives anything but a valid proof sends nothing further, so no file reaches a process that does
 * not hold the token.
 */
public final class InstanceClient {
  /** How long a missing endpoint file is waited for: the primary writes it right after binding. */
  public static final Duration ENDPOINT_WAIT = Duration.ofSeconds(2);

  /** Longest connect. */
  public static final Duration CONNECT_BOUND = Duration.ofSeconds(1);

  /** Longest whole exchange. */
  public static final Duration EXCHANGE_BOUND = Duration.ofSeconds(5);

  private static final long ENDPOINT_POLL_MILLIS = 50;

  private InstanceClient() {}

  /**
   * Hands a launch to the primary instance: its files with their options, {@code -n} with the
   * window geometry, and {@code --diff}.
   *
   * @param directory the settings directory
   * @param request the command line; absolute paths, at most {@link Protocol#MAX_OPEN} files
   * @throws IOException when any step fails or the primary answers ERR; the files are then not
   *     known to have reached it
   */
  public static void handOff(Path directory, StartupRequest request) throws IOException {
    if (request.files().size() > Protocol.MAX_OPEN) {
      throw new IOException("More than " + Protocol.MAX_OPEN + " files");
    }
    Endpoint endpoint = awaitEndpoint(directory);
    long deadline = System.nanoTime() + EXCHANGE_BOUND.toNanos();
    try (LineChannel line = LineChannel.connect(endpoint.port(), CONNECT_BOUND, deadline)) {
      byte[] nonceC = Protocol.secret();
      line.writeLine(Protocol.HELLO + Protocol.encode(nonceC));
      String proof = line.readLine();
      String[] parts =
          proof.startsWith(Protocol.PROOF)
              ? proof.substring(Protocol.PROOF.length()).split(" ", -1)
              : new String[0];
      if (parts.length != 2) {
        throw new IOException("The listener did not prove the token");
      }
      byte[] nonceS = Protocol.decode(parts[0]);
      if (nonceS.length != Protocol.SECRET_BYTES
          || !Protocol.matches(
              Protocol.proof(endpoint.token(), "server", nonceC, nonceS),
              Protocol.decode(parts[1]))) {
        throw new IOException("The listener did not prove the token");
      }
      line.writeLine(
          Protocol.AUTH
              + Protocol.encode(Protocol.proof(endpoint.token(), "client", nonceS, nonceC)));
      line.writeLine(window(request));
      for (FileRequest file : request.files()) {
        line.writeLine(open(file));
      }
      if (request.diffLeft() != null) {
        line.writeLine(Protocol.DIFF + text(request.diffLeft()) + " " + text(request.diffRight()));
      }
      line.writeLine(Protocol.END);
      String reply = line.readLine();
      if (!Protocol.OK.equals(reply)) {
        throw new IOException("The primary instance refused the files");
      }
    }
  }

  private static String window(StartupRequest request) {
    return Protocol.WINDOW
        + " "
        + (request.newWindow() ? "1" : "0")
        + " "
        + number(request.x())
        + " "
        + number(request.y())
        + " "
        + number(request.width())
        + " "
        + number(request.height());
  }

  private static String open(FileRequest file) {
    return Protocol.OPEN
        + text(file.path())
        + " "
        + file.line()
        + " "
        + file.column()
        + " "
        + (file.readOnly() ? "1" : "0")
        + " "
        + (file.encoding() == null ? Protocol.NONE : text(file.encoding()));
  }

  private static String number(Integer value) {
    return value == null ? Protocol.NONE : value.toString();
  }

  private static String text(Object value) {
    return Protocol.encode(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static Endpoint awaitEndpoint(Path directory) throws IOException {
    long deadline = System.nanoTime() + ENDPOINT_WAIT.toNanos();
    while (true) {
      try {
        return Endpoint.read(directory);
      } catch (NoSuchFileException e) {
        if (System.nanoTime() >= deadline) {
          throw new IOException("No endpoint file", e);
        }
        try {
          Thread.sleep(ENDPOINT_POLL_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", interrupted);
        }
      }
    }
  }

  /**
   * Checks that the handoff may use a settings directory: on POSIX it must belong to the current
   * user and be writable by no one else.
   *
   * @param directory the settings directory
   * @param user the current user's name
   * @return null when it may, else the reason
   */
  public static String refusal(Path directory, String user) {
    if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return null;
    }
    try {
      PosixFileAttributes attributes =
          Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      UserPrincipal me =
          directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
      if (!attributes.owner().equals(me)) {
        return "the settings directory belongs to another user";
      }
      Set<PosixFilePermission> permissions = attributes.permissions();
      if (permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
        return "the settings directory is writable by other users";
      }
      return null;
    } catch (IOException e) {
      return "the settings directory cannot be checked";
    }
  }
}
