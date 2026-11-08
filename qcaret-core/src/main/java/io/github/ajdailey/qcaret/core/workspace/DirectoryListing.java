package io.github.ajdailey.qcaret.core.workspace;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One directory's entries as the workspace browser lists them: directories first, then files, each
 * in {@link ListingOptions#order()}, without the entries {@link WalkRules} leaves out. A link to a
 * directory lists as a directory unless its target contains the link ({@link Kind#CYCLE}); a link
 * to a file, or a broken link, lists as a file, with its target's size and time where it resolves.
 * At most {@value #MAX_ENTRIES} entries are read. Immutable.
 */
public final class DirectoryListing {
  /** Most entries read from one directory. */
  public static final int MAX_ENTRIES = 50_000;

  /** What an entry is. */
  public enum Kind {
    /** A directory, or a link to one. */
    DIRECTORY,
    /** Anything else. */
    FILE,
    /** A link to a directory that contains the link; never listed further. */
    CYCLE
  }

  /**
   * One entry.
   *
   * @param name its file name
   * @param kind what it is
   * @param size its size in bytes
   * @param modified its last-modified time in milliseconds since the epoch
   */
  public record Entry(String name, Kind kind, long size, long modified) {}

  private static int byName(Entry a, Entry b) {
    int byCase = a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT));
    return byCase != 0 ? byCase : a.name().compareTo(b.name());
  }

  /** Returns the order of {@code options}: directories first, ties by name. */
  private static Comparator<Entry> order(final ListingOptions options) {
    return new Comparator<Entry>() {
      @Override
      public int compare(Entry a, Entry b) {
        boolean aFile = a.kind() == Kind.FILE;
        boolean bFile = b.kind() == Kind.FILE;
        if (aFile != bFile) {
          return aFile ? 1 : -1;
        }
        int key =
            switch (options.order()) {
              case NAME -> 0;
              case SIZE -> aFile ? Long.compare(a.size(), b.size()) : 0;
              case MODIFIED -> Long.compare(a.modified(), b.modified());
            };
        if (key == 0) {
          key = byName(a, b);
        }
        return options.descending() ? -key : key;
      }
    };
  }

  private final List<Entry> entries;
  private final boolean truncated;

  private DirectoryListing(List<Entry> entries, boolean truncated) {
    this.entries = entries;
    this.truncated = truncated;
  }

  /**
   * Reads a directory in the default order: every file, no hidden entries, by name.
   *
   * @param directory the directory
   * @param rules the entries left out
   * @param token checked per entry
   * @return the listing
   * @throws IOException when the directory cannot be read
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static DirectoryListing read(Path directory, WalkRules rules, CancellationToken token)
      throws IOException {
    return read(directory, rules, ListingOptions.DEFAULTS, token);
  }

  /**
   * Reads a directory.
   *
   * @param directory the directory
   * @param rules the entries left out
   * @param options the filter, hidden entries and order
   * @param token checked per entry
   * @return the listing
   * @throws IOException when the directory cannot be read
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static DirectoryListing read(
      Path directory, WalkRules rules, ListingOptions options, CancellationToken token)
      throws IOException {
    List<Entry> list = new ArrayList<Entry>();
    boolean cut = false;
    Path real = null;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        token.check();
        if (list.size() == MAX_ENTRIES) {
          cut = true;
          break;
        }
        BasicFileAttributes attrs;
        try {
          attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
          continue;
        }
        Kind kind = attrs.isDirectory() ? Kind.DIRECTORY : Kind.FILE;
        if (attrs.isSymbolicLink()) {
          attrs = target(entry, attrs);
          if (attrs.isDirectory()) {
            if (real == null) {
              real = directory.toRealPath();
            }
            kind = cycle(real, entry) ? Kind.CYCLE : Kind.DIRECTORY;
          }
        }
        String name = String.valueOf(entry.getFileName());
        boolean directoryKind = kind != Kind.FILE;
        boolean left =
            options.hidden()
                ? rules.excludes(entry, directoryKind)
                : rules.skips(entry, directoryKind);
        if (!left && (directoryKind || options.filter().matches(name))) {
          list.add(new Entry(name, kind, attrs.size(), attrs.lastModifiedTime().toMillis()));
        }
      }
    } catch (DirectoryIteratorException e) {
      throw e.getCause();
    }
    Collections.sort(list, order(options));
    return new DirectoryListing(Collections.unmodifiableList(list), cut);
  }

  /** Returns a link's target's attributes, or the link's own when it does not resolve. */
  private static BasicFileAttributes target(Path link, BasicFileAttributes own) {
    try {
      return Files.readAttributes(link, BasicFileAttributes.class);
    } catch (IOException e) {
      return own;
    }
  }

  /** Reports whether a link's target contains the directory that holds the link. */
  private static boolean cycle(Path directoryReal, Path link) {
    try {
      return directoryReal.startsWith(link.toRealPath());
    } catch (IOException e) {
      return true;
    }
  }

  /**
   * Returns the entries.
   *
   * @return directories first, then files, each in the listing's order
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Reports whether the directory held more than {@value #MAX_ENTRIES} entries.
   *
   * @return true when entries were left unread
   */
  public boolean truncated() {
    return truncated;
  }
}
