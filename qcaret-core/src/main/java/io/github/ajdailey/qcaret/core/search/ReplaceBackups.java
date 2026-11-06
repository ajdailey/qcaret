package io.github.ajdailey.qcaret.core.search;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.io.ExternalModificationException;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The backups of one replace run, under {@code <settings>/replace-backups/<run>/}: one file {@code
 * <n>.bak} per replaced file, a header line with the file's path and the SHA-256 of its bytes
 * before and after the replace, then the original bytes. The run's name is its UTC start time,
 * {@code yyyyMMdd-HHmmss-SSS}. Directories are created owner-only where POSIX permissions exist.
 *
 * <p>{@link #record} writes and forces a backup before its file is replaced, so a crash during the
 * write leaves the original recoverable; a write that does not happen deletes its backup. {@link
 * #restore} writes a backup back only while the file holds the bytes the run left; a file changed
 * since is refused. {@link #prune} deletes runs older than the days kept. Every method that touches
 * the disk runs on a worker; {@link #record} may run on several at once.
 */
public final class ReplaceBackups {
  /** The backups' directory under the settings directory. */
  public static final String DIRECTORY = "replace-backups";

  /** Days a run's backups are kept ({@code replace.backupDays}). */
  public static final int DEFAULT_DAYS = 7;

  private static final String HEADER = "qcaret-backup\t1";
  private static final String SUFFIX = ".bak";
  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS");
  private static final int NAME_LENGTH = 19;
  private static final int BLOCK_BYTES = 1 << 20;

  private final String id;
  private final Path directory;
  private final AtomicInteger next = new AtomicInteger(1);

  private ReplaceBackups(String id, Path directory) {
    this.id = id;
    this.directory = directory;
  }

  /**
   * One run on disk.
   *
   * @param id the run's name
   * @param directory its directory
   * @param started its start time
   * @param files the files it backed up, dropped ones excluded
   */
  public record Run(String id, Path directory, Instant started, int files) {}

  /**
   * One backed-up file.
   *
   * @param number the backup's number, its file {@code <number>.bak}
   * @param file the file's real path when replaced
   * @param before the SHA-256 of the original bytes
   * @param after the SHA-256 of the bytes the run wrote
   */
  public record Entry(int number, Path file, byte[] before, byte[] after) {}

  /** What {@link #restore} did with a file. */
  public enum Restore {
    /** The backup was written back. */
    RESTORED,
    /** The file already holds the original bytes. */
    UNCHANGED,
    /** The file changed after the run; left as it is. */
    CHANGED_SINCE,
    /** The file does not exist; left missing. */
    MISSING,
    /** Reading the backup or writing the file failed. */
    FAILED
  }

  /**
   * A restored file.
   *
   * @param file the file
   * @param result what happened
   * @param error the failure, or null
   */
  public record Restored(Path file, Restore result, IOException error) {}

  /**
   * Creates a run's directory.
   *
   * @param root the backups' directory, created when missing
   * @param now the start time, which names the run
   * @return the run
   * @throws IOException when a directory cannot be created
   */
  @WorkerThread
  public static ReplaceBackups start(Path root, Instant now) throws IOException {
    Path base = SafePaths.settingsDirectory(root);
    String name = NAME.format(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    String id = name;
    for (int n = 2; Files.exists(base.resolve(id)); n++) {
      id = name + "-" + n;
    }
    return new ReplaceBackups(id, SafePaths.settingsDirectory(SafePaths.child(base, id)));
  }

  /**
   * Returns the run's name.
   *
   * @return the name
   */
  public String id() {
    return id;
  }

  /**
   * Returns the run's directory.
   *
   * @return the directory
   */
  public Path directory() {
    return directory;
  }

  /**
   * Writes a file's original bytes behind a header naming the file, forced to disk.
   *
   * @param file the file's real path
   * @param original its bytes before the replace
   * @param after the SHA-256 of the bytes about to be written
   * @return the backup's number, for {@link #dropQuietly}
   * @throws IOException when the backup cannot be written; the file must then stay untouched
   */
  @WorkerThread
  public int record(Path file, byte[] original, byte[] after) throws IOException {
    int number = next.getAndIncrement();
    HexFormat hex = HexFormat.of();
    String header =
        HEADER
            + "\t"
            + hex.formatHex(FileSearch.digest(original))
            + "\t"
            + hex.formatHex(after)
            + "\t"
            + escape(file.toString())
            + "\n";
    try (FileChannel out =
        FileChannel.open(
            directory.resolve(number + SUFFIX),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
      for (ByteBuffer buffer :
          new ByteBuffer[] {
            ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(original)
          }) {
        while (buffer.hasRemaining()) {
          out.write(buffer);
        }
      }
      out.force(true);
    }
    return number;
  }

  /**
   * Deletes a backup whose write did not happen. A failure is ignored: the backup then restores as
   * {@link Restore#UNCHANGED}.
   *
   * @param number the backup's number
   */
  @WorkerThread
  public void dropQuietly(int number) {
    try {
      Files.deleteIfExists(directory.resolve(number + SUFFIX));
    } catch (IOException e) {
      // The backup stays; its file holds the original bytes.
    }
  }

  /**
   * Lists the runs on disk, newest first; a directory whose name is not a run's is ignored.
   *
   * @param root the backups' directory
   * @return the runs, empty when the directory does not exist
   * @throws IOException when the directory cannot be listed
   */
  @WorkerThread
  public static List<Run> list(Path root) throws IOException {
    List<Run> runs = new ArrayList<Run>();
    if (!Files.isDirectory(root)) {
      return runs;
    }
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
      for (Path dir : dirs) {
        Instant started = started(dir);
        if (started != null && Files.isDirectory(dir)) {
          runs.add(new Run(String.valueOf(dir.getFileName()), dir, started, entries(dir).size()));
        }
      }
    }
    runs.sort(
        new Comparator<Run>() {
          @Override
          public int compare(Run a, Run b) {
            int byTime = b.started().compareTo(a.started());
            return byTime != 0 ? byTime : b.id().compareTo(a.id());
          }
        });
    return runs;
  }

  /**
   * Reads a run's backup headers; a backup cut short by a crash is left out.
   *
   * @param run the run's directory
   * @return the entries by number
   * @throws IOException when the directory cannot be listed
   */
  @WorkerThread
  public static List<Entry> entries(Path run) throws IOException {
    List<Entry> entries = new ArrayList<Entry>();
    HexFormat hex = HexFormat.of();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(run, "*" + SUFFIX)) {
      for (Path file : files) {
        String name = String.valueOf(file.getFileName());
        try {
          int number = Integer.parseInt(name.substring(0, name.length() - SUFFIX.length()));
          String[] parts = header(file).split("\t", -1);
          if (parts.length == 5 && (parts[0] + "\t" + parts[1]).equals(HEADER)) {
            entries.add(
                new Entry(
                    number,
                    Path.of(unescape(parts[4])),
                    hex.parseHex(parts[2]),
                    hex.parseHex(parts[3])));
          }
        } catch (IllegalArgumentException e) {
          // Not a backup, or its header was cut short.
        }
      }
    }
    entries.sort(
        new Comparator<Entry>() {
          @Override
          public int compare(Entry a, Entry b) {
            return Integer.compare(a.number(), b.number());
          }
        });
    return entries;
  }

  /** Reads a backup's header line; empty when it has none. */
  private static String header(Path backup) throws IOException {
    byte[] start;
    try (FileChannel in = FileChannel.open(backup, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
      while (buffer.hasRemaining() && in.read(buffer) >= 0) {
        // Fill the buffer: a header is a path and two digests.
      }
      start = Arrays.copyOf(buffer.array(), buffer.position());
    }
    for (int i = 0; i < start.length; i++) {
      if (start[i] == '\n') {
        return new String(start, 0, i, StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  /**
   * Writes one backup back: only while the file holds the bytes the run wrote, through {@link
   * SafeSave} with the stamp read before those bytes.
   *
   * @param run the run's directory
   * @param entry the file
   * @param token checked per block of the reads and before the write
   * @return what happened
   */
  @WorkerThread
  public static Restored restore(Path run, Entry entry, CancellationToken token) {
    Path file = entry.file();
    try {
      FileStamp stamp = FileStamp.read(file);
      if (stamp == null) {
        return new Restored(file, Restore.MISSING, null);
      }
      byte[] digest = Digests.bytesOf(file, token);
      if (MessageDigest.isEqual(digest, entry.before())) {
        return new Restored(file, Restore.UNCHANGED, null);
      }
      if (!MessageDigest.isEqual(digest, entry.after())) {
        return new Restored(file, Restore.CHANGED_SINCE, null);
      }
      final Path backup = run.resolve(entry.number() + SUFFIX);
      MessageDigest original = Digests.sha256();
      copyBody(backup, new DigestOutputStream(OutputStream.nullOutputStream(), original), token);
      if (!MessageDigest.isEqual(original.digest(), entry.before())) {
        return new Restored(file, Restore.FAILED, new IOException("The backup is damaged"));
      }
      token.check();
      SafeSave.save(
          file,
          stamp,
          new ContentWriter() {
            @Override
            public void write(OutputStream out) throws IOException {
              // Not canceled: SafeSave may be writing the target in place.
              copyBody(backup, out, new CancellationToken());
            }
          },
          token);
      return new Restored(file, Restore.RESTORED, null);
    } catch (ExternalModificationException e) {
      return new Restored(file, Restore.CHANGED_SINCE, null);
    } catch (IOException e) {
      return new Restored(file, Restore.FAILED, e);
    }
  }

  /** Writes a backup's bytes after its header line, block by block; none without a header. */
  private static void copyBody(Path backup, OutputStream out, CancellationToken token)
      throws IOException {
    byte[] block = new byte[BLOCK_BYTES];
    boolean body = false;
    try (InputStream in = Files.newInputStream(backup)) {
      while (true) {
        token.check();
        int n = in.read(block);
        if (n < 0) {
          return;
        }
        int from = 0;
        while (!body && from < n) {
          body = block[from++] == '\n';
        }
        out.write(block, from, n - from);
      }
    }
  }

  /**
   * Deletes the runs that started more than {@code days} days before {@code now}.
   *
   * @param root the backups' directory
   * @param days the days a run is kept
   * @param now the current time
   * @return the runs deleted
   */
  @WorkerThread
  public static int prune(Path root, int days, Instant now) {
    Instant oldest = now.minus(Duration.ofDays(days));
    int deleted = 0;
    if (!Files.isDirectory(root)) {
      return 0;
    }
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
      for (Path dir : dirs) {
        Instant started = started(dir);
        if (started != null && started.isBefore(oldest) && Files.isDirectory(dir)) {
          try {
            delete(dir);
            deleted++;
          } catch (IOException e) {
            // Tried again at the next run.
          }
        }
      }
    } catch (IOException e) {
      // Tried again at the next run.
    }
    return deleted;
  }

  /** Reads a run's start time from its directory name; null for another name. */
  static Instant started(Path dir) {
    Path name = dir.getFileName();
    String text = name == null ? "" : name.toString();
    int length = NAME_LENGTH;
    if (text.length() < length) {
      return null;
    }
    String suffix = text.substring(length);
    if (!suffix.isEmpty() && !suffix.matches("-[0-9]+")) {
      return null;
    }
    try {
      return LocalDateTime.parse(text.substring(0, length), NAME).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static void delete(Path dir) throws IOException {
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path visited, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.delete(visited);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Escapes a backslash, tab, CR and LF so a path fits one header field. */
  static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\t' -> out.append("\\t");
        case '\r' -> out.append("\\r");
        case '\n' -> out.append("\\n");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  static String unescape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' && i + 1 < text.length()) {
        char next = text.charAt(++i);
        out.append(next == 't' ? '\t' : next == 'r' ? '\r' : next == 'n' ? '\n' : next);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
