package io.github.ajdailey.qcaret.core.workspace;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The files under a folder for Quick Open: each file's path relative to the root, elements joined
 * with {@code /}. The walk follows no link to a directory, so a link cycle is never entered; a link
 * to a file is listed. {@link WalkRules} leaves out hidden entries and excluded directories. Each
 * path reserves {@value #ENTRY_BYTES} bytes plus 2 per char from the {@link MemoryBudget}; the walk
 * stops at the file limit or at a refused reservation, and the index reports which. Immutable; the
 * owner returns {@link #charged()} to the budget when it drops the index.
 */
public final class FileIndex {
  /** Files indexed at most when the caller gives no other limit. */
  public static final int MAX_FILES = 200_000;

  /** Bytes charged per path besides its chars. */
  public static final long ENTRY_BYTES = 64;

  /** Why an index holds fewer files than the folder. */
  public enum Cut {
    /** Every file is indexed. */
    NONE,
    /** The file limit was reached. */
    LIMIT,
    /** The budget refused a path. */
    MEMORY
  }

  private final Path root;
  private final String[] paths;
  private final Cut cut;
  private final long charged;

  private FileIndex(Path root, String[] paths, Cut cut, long charged) {
    this.root = root;
    this.paths = paths;
    this.cut = cut;
    this.charged = charged;
  }

  /**
   * Returns an index of no files.
   *
   * @param root the folder
   * @return the index, charging nothing
   */
  public static FileIndex empty(Path root) {
    return new FileIndex(root, new String[0], Cut.NONE, 0);
  }

  /**
   * Walks a folder. An unreadable directory is skipped; an unreadable root gives an empty index.
   *
   * @param root the folder's real path
   * @param rules the entries left out
   * @param limit most files indexed
   * @param budget charged for each path
   * @param token checked per entry
   * @return the index
   * @throws CancellationException when canceled; nothing stays charged
   */
  @WorkerThread
  public static FileIndex build(
      final Path root,
      final WalkRules rules,
      final int limit,
      final MemoryBudget budget,
      final CancellationToken token) {
    final List<String> found = new ArrayList<String>();
    final long[] reserved = new long[1];
    final Cut[] cut = {Cut.NONE};
    boolean done = false;
    try {
      Files.walkFileTree(
          root,
          EnumSet.noneOf(FileVisitOption.class),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              token.check();
              return dir.equals(root) || !rules.skips(dir, true)
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              token.check();
              boolean listed =
                  attrs.isRegularFile() || (attrs.isSymbolicLink() && !Files.isDirectory(file));
              if (!listed || rules.skips(file, false)) {
                return FileVisitResult.CONTINUE;
              }
              if (found.size() >= limit) {
                cut[0] = Cut.LIMIT;
                return FileVisitResult.TERMINATE;
              }
              String relative = relative(root, file);
              long cost = ENTRY_BYTES + 2L * relative.length();
              if (!budget.reserve(cost)) {
                cut[0] = Cut.MEMORY;
                return FileVisitResult.TERMINATE;
              }
              reserved[0] += cost;
              found.add(relative);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
              token.check();
              return FileVisitResult.CONTINUE;
            }
          });
      done = true;
    } catch (IOException e) {
      done = true;
    } finally {
      if (!done) {
        budget.release(reserved[0]);
      }
    }
    return new FileIndex(root, found.toArray(new String[0]), cut[0], reserved[0]);
  }

  /**
   * Returns this index with one file taken out and one put in, for a file created, renamed or
   * deleted below the root. The new index charges for the added path; the caller returns this
   * index's charge when it drops it.
   *
   * @param removed a file below the root, or null
   * @param added a file below the root that the walk would list, or null
   * @param budget charged for the added path
   * @return the new index; null when this one is cut or full, a path lies outside the root, or the
   *     budget refuses, and the caller builds it again
   */
  public FileIndex withChange(Path removed, Path added, MemoryBudget budget) {
    if (cut != Cut.NONE
        || paths.length >= MAX_FILES
        || (removed != null && !below(removed))
        || (added != null && !below(added))) {
      return null;
    }
    String gone = removed == null ? null : relative(root, removed);
    String put = added == null ? null : relative(root, added);
    List<String> next = new ArrayList<String>(paths.length + 1);
    long charge = 0;
    for (String path : paths) {
      if (path.equals(gone)) {
        continue;
      }
      if (path.equals(put)) {
        put = null;
      }
      next.add(path);
      charge += ENTRY_BYTES + 2L * path.length();
    }
    if (put != null) {
      next.add(put);
      charge += ENTRY_BYTES + 2L * put.length();
    }
    if (!budget.reserve(charge)) {
      return null;
    }
    return new FileIndex(root, next.toArray(new String[0]), Cut.NONE, charge);
  }

  private boolean below(Path file) {
    return file.startsWith(root) && !file.equals(root);
  }

  /** Joins a file's path elements below the root with {@code /}. */
  private static String relative(Path root, Path file) {
    Path relative = root.relativize(file);
    StringBuilder text = new StringBuilder();
    for (Path element : relative) {
      if (text.length() > 0) {
        text.append('/');
      }
      text.append(element);
    }
    return text.toString();
  }

  /**
   * Returns the folder.
   *
   * @return the root the paths are relative to
   */
  public Path root() {
    return root;
  }

  /**
   * Returns the number of files.
   *
   * @return the count
   */
  public int size() {
    return paths.length;
  }

  /**
   * Returns a file's path below the root.
   *
   * @param index from 0 to {@link #size()} - 1
   * @return elements joined with {@code /}
   */
  public String path(int index) {
    return paths[index];
  }

  /**
   * Returns a file's absolute path.
   *
   * @param index from 0 to {@link #size()} - 1
   * @return the root resolved against the relative path
   */
  public Path resolve(int index) {
    Path out = root;
    for (String element : paths[index].split("/", -1)) {
      out = out.resolve(element);
    }
    return out;
  }

  /**
   * Reports why files were left out.
   *
   * @return {@link Cut#NONE} when the index is complete
   */
  public Cut cut() {
    return cut;
  }

  /**
   * Returns the bytes the paths reserve.
   *
   * @return the charge
   */
  public long charged() {
    return charged;
  }
}
