package io.github.ajdailey.qcaret.app.recovery;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.ProcessLock;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The directories under {@code <settings>/recovery/}, one per instance, owner-only, each with an
 * {@code owner.lock} its instance holds through an OS lock for its life. The lock decides whether
 * an instance is alive; the presence of a directory says nothing. {@link SafePaths#holdLock} keeps
 * a held {@code owner.lock} out of every open and save: a save replaces the file, the lock stays on
 * the old one, and the next start lists a live instance's buffers as a crash's. {@code
 * <settings>/snapshot/} holds the buffers of a quit with {@code session.snapshot} on. Every method
 * blocks and runs on a worker; the caller serializes them.
 */
public final class RecoveryStore {
  /** The recovery directory's name in the settings directory. */
  public static final String DIRECTORY = "recovery";

  /** The snapshot directory's name in the settings directory. */
  public static final String SNAPSHOT = "snapshot";

  /** The lock file's name in an instance directory. */
  public static final String LOCK = "owner.lock";

  private static final Logger LOG = LoggerFactory.getLogger(RecoveryStore.class);

  /** How a recovered buffer's original file stands. */
  public enum Original {
    /** The buffer has no file. */
    NONE,
    /** The file holds the bytes the buffer started from. */
    UNCHANGED,
    /** The file changed since. */
    CHANGED,
    /** The file is gone. */
    MISSING,
    /** The file exists and cannot be read. */
    UNREADABLE
  }

  /**
   * One file of a dead instance.
   *
   * @param file the recovery file
   * @param header its header; null when the header does not parse
   * @param damaged true when the file is not complete or its trailer digest does not match
   * @param original how the original file stands
   * @param unreadable true when the recovery file cannot be read; header is then null
   */
  public record Entry(
      Path file,
      RecoveryFile.Header header,
      boolean damaged,
      Original original,
      boolean unreadable) {}

  /** A dead instance's directory, locked until {@link #close}, with the files it holds. */
  public static final class Claim implements AutoCloseable {
    private final Path directory;
    private final ProcessLock lock;
    private final List<Entry> entries;

    Claim(Path directory, ProcessLock lock, List<Entry> entries) {
      this.directory = directory;
      this.lock = lock;
      this.entries = List.copyOf(entries);
    }

    /**
     * Returns the files.
     *
     * @return the entries, oldest first by file name
     */
    public List<Entry> entries() {
      return entries;
    }

    /** Releases the lock, then deletes the directory when no recovery file is left. */
    @Override
    public void close() {
      release(directory, lock);
      try {
        if (recoveryFiles(directory).isEmpty()) {
          deleteDirectory(directory);
        }
      } catch (IOException e) {
        LOG.warn("Cannot clean a recovery directory: {}", LogText.oneLine(e.getMessage()));
      }
    }
  }

  private final Path root;
  private final Path snapshots;
  private final Path own;
  private ProcessLock lock;

  /**
   * Creates the store; nothing is touched until {@link #open}.
   *
   * @param settings the settings directory
   * @param instance this instance's directory name, a plain file name
   * @throws IOException when the name is not plain
   */
  public RecoveryStore(Path settings, String instance) throws IOException {
    this.root = SafePaths.child(settings, DIRECTORY);
    this.snapshots = SafePaths.child(settings, SNAPSHOT);
    this.own = SafePaths.child(root, instance);
  }

  /**
   * Creates this instance's directory and takes its lock.
   *
   * @throws IOException when the directory cannot be created or locked
   */
  public void open() throws IOException {
    SafePaths.settingsDirectory(own);
    ProcessLock taken = ProcessLock.tryAcquire(own.resolve(LOCK));
    if (taken == null) {
      throw new IOException("Recovery directory is locked");
    }
    try {
      hold(own);
    } catch (IOException e) {
      release(own, taken);
      throw e;
    }
    lock = taken;
  }

  /**
   * Reports whether {@link #open} succeeded and {@link #close} has not run.
   *
   * @return true while the lock is held
   */
  public boolean isOpen() {
    return lock != null;
  }

  /**
   * Writes a buffer's file.
   *
   * @param header the header
   * @param text the buffer's text
   * @throws IOException when the write fails; an older file for the buffer stays
   */
  public void write(RecoveryFile.Header header, TextSnapshot text) throws IOException {
    RecoveryFile.write(file(own, header.id()), header, text);
  }

  /**
   * Deletes a buffer's file.
   *
   * @param id the buffer's id
   * @throws IOException when the file cannot be deleted
   */
  public void delete(String id) throws IOException {
    Files.deleteIfExists(file(own, id));
  }

  /**
   * Releases the lock and deletes this instance's directory, or keeps it for the next start.
   *
   * @param keep true to keep the directory, which then belongs to a dead instance
   */
  public void close(boolean keep) {
    if (lock == null) {
      return;
    }
    release(own, lock);
    lock = null;
    if (!keep) {
      try {
        deleteDirectory(own);
      } catch (IOException e) {
        LOG.warn("Cannot delete the recovery directory: {}", LogText.oneLine(e.getMessage()));
      }
    }
  }

  /**
   * Finds the directories of dead instances: each whose {@code owner.lock} can be acquired. Files
   * older than the limit are deleted; a directory left without files goes too. The others are read
   * and the directory stays locked until its claim closes, so a second start lists it only then. A
   * file that cannot be read is listed as unreadable and logged; the directory's other files are
   * listed as usual.
   *
   * @param oldest the modification time, in milliseconds, before which a file is deleted
   * @return the claims
   * @throws IOException when the recovery directory cannot be listed
   */
  @WorkerThread
  public List<Claim> claimDead(long oldest) throws IOException {
    List<Claim> claims = new ArrayList<Claim>();
    if (!Files.isDirectory(root)) {
      return claims;
    }
    List<Path> directories = new ArrayList<Path>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
      for (Path entry : stream) {
        if (Files.isDirectory(entry)) {
          directories.add(entry);
        }
      }
    }
    for (Path directory : directories) {
      Claim claim = claim(directory, oldest);
      if (claim != null) {
        claims.add(claim);
      }
    }
    return claims;
  }

  @WorkerThread
  private static Claim claim(Path directory, long oldest) {
    ProcessLock taken = null;
    try {
      taken = ProcessLock.tryAcquire(directory.resolve(LOCK));
      if (taken == null) {
        return null;
      }
      hold(directory);
      List<Entry> entries = new ArrayList<Entry>();
      for (Path file : recoveryFiles(directory)) {
        Entry entry = entry(file, oldest);
        if (entry != null) {
          entries.add(entry);
        }
      }
      Claim claim = new Claim(directory, taken, entries);
      if (entries.isEmpty()) {
        claim.close();
        return null;
      }
      return claim;
    } catch (IOException e) {
      LOG.warn("Cannot read a recovery directory: {}", LogText.oneLine(e.getMessage()));
      release(directory, taken);
      return null;
    }
  }

  /** Returns a file's entry, or null when it is past the limit and deleted. */
  @WorkerThread
  private static Entry entry(Path file, long oldest) {
    try {
      if (Files.getLastModifiedTime(file).toMillis() >= oldest) {
        return entry(file);
      }
    } catch (IOException e) {
      LOG.warn(
          "Cannot read recovery file {}: {}",
          LogText.oneLine(file.getFileName()),
          LogText.oneLine(e.getMessage()));
      return new Entry(file, null, false, Original.NONE, true);
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("Cannot delete an expired recovery file: {}", LogText.oneLine(e.getMessage()));
    }
    return null;
  }

  @WorkerThread
  private static Entry entry(Path file) throws IOException {
    try {
      RecoveryFile.Header header = RecoveryFile.check(file);
      return new Entry(file, header, false, original(header), false);
    } catch (RecoveryFile.DamagedException e) {
      LOG.warn(
          "Damaged recovery file {}: {}",
          LogText.oneLine(file.getFileName()),
          LogText.oneLine(e.getMessage()));
      RecoveryFile.Header header;
      try {
        header = RecoveryFile.header(file);
      } catch (RecoveryFile.DamagedException unreadable) {
        header = null;
      }
      return new Entry(file, header, true, Original.NONE, false);
    }
  }

  /** Compares the original file with the base the header recorded; a failure hides no buffer. */
  @WorkerThread
  private static Original original(RecoveryFile.Header header) {
    if (header.path().isEmpty()) {
      return Original.NONE;
    }
    Path path;
    try {
      path = Path.of(header.path());
    } catch (InvalidPathException e) {
      return Original.MISSING;
    }
    try {
      FileStamp stamp = FileStamp.read(path);
      if (stamp == null) {
        return header.baseSize() < 0 ? Original.NONE : Original.MISSING;
      }
      if (stamp.size() != header.baseSize()) {
        return Original.CHANGED;
      }
      boolean same = Digests.of(path, new CancellationToken()).equals(header.baseSha256());
      return same ? Original.UNCHANGED : Original.CHANGED;
    } catch (NoSuchFileException e) {
      return Original.MISSING;
    } catch (IOException e) {
      LOG.warn("Cannot read a recovered buffer's file: {}", LogText.oneLine(e.getMessage()));
      return Original.UNREADABLE;
    }
  }

  /** Keeps a directory's lock file out of every open and save while this process holds it. */
  private static void hold(Path directory) throws IOException {
    SafePaths.holdLock(directory.resolve(LOCK), UiText.RECOVERY_LOCK_REFUSED.text());
  }

  private static void release(Path directory, ProcessLock held) {
    SafePaths.releaseLock(directory.resolve(LOCK));
    try {
      if (held != null) {
        held.close();
      }
    } catch (IOException e) {
      LOG.warn("Cannot release {}: {}", LOCK, LogText.oneLine(e.getMessage()));
    }
  }

  private static List<Path> recoveryFiles(Path directory) throws IOException {
    List<Path> files = new ArrayList<Path>();
    try (DirectoryStream<Path> stream =
        Files.newDirectoryStream(directory, "*" + RecoveryFile.SUFFIX)) {
      for (Path file : stream) {
        files.add(file);
      }
    }
    files.sort(Comparator.naturalOrder());
    return files;
  }

  private static void deleteDirectory(Path directory) throws IOException {
    List<Path> files = new ArrayList<Path>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path file : stream) {
        files.add(file);
      }
    }
    for (Path file : files) {
      Files.deleteIfExists(file);
    }
    Files.deleteIfExists(directory);
  }

  private static Path file(Path directory, String id) throws IOException {
    return SafePaths.child(directory, id + RecoveryFile.SUFFIX);
  }

  // Snapshots.

  /**
   * Writes a buffer's snapshot file.
   *
   * @param header the header
   * @param text the buffer's text
   * @throws IOException when the write fails
   */
  public void writeSnapshot(RecoveryFile.Header header, TextSnapshot text) throws IOException {
    SafePaths.settingsDirectory(snapshots);
    RecoveryFile.write(file(snapshots, header.id()), header, text);
  }

  /**
   * Returns a buffer's snapshot file.
   *
   * @param settings the settings directory
   * @param id the buffer's id
   * @return the file, which may not exist
   * @throws IOException when the id is not a plain name
   */
  public static Path snapshotFile(Path settings, String id) throws IOException {
    return file(SafePaths.child(settings, SNAPSHOT), id);
  }

  /**
   * Deletes a recovery or snapshot file.
   *
   * @param file the file
   * @throws IOException when it cannot be deleted
   */
  public static void deleteFile(Path file) throws IOException {
    Files.deleteIfExists(file);
  }

  /**
   * Deletes every snapshot file but some.
   *
   * @param kept the ids to keep
   * @throws IOException when the directory cannot be listed
   */
  public void pruneSnapshots(Set<String> kept) throws IOException {
    if (!Files.isDirectory(snapshots)) {
      return;
    }
    for (Path file : recoveryFiles(snapshots)) {
      String name = String.valueOf(file.getFileName());
      String id = name.substring(0, name.length() - RecoveryFile.SUFFIX.length());
      if (!kept.contains(id)) {
        Files.deleteIfExists(file);
      }
    }
  }
}
