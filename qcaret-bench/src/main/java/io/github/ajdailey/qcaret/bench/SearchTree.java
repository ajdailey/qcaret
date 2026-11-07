package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A generated tree under a temporary directory: seeded {@link Corpus#ASCII} files, small ones
 * spread over directories of {@value #FILES_PER_DIRECTORY} and large ones in {@code large/}. Every
 * {@value #RARE_EVERY}th small file and every large file holds {@value #RARE} once. {@link #close}
 * deletes the tree.
 */
public final class SearchTree implements Closeable {
  /** Small files of the benchmarks' tree. */
  static final int SMALL_FILES = 20_000;

  /** Chars of a small file. */
  static final int SMALL_CHARS = 2048;

  /** Large files of the benchmarks' tree. */
  static final int LARGE_FILES = 4;

  /** Chars of a large file: under the default {@code search.maxFileBytes}. */
  static final int LARGE_CHARS = 16 * Corpus.MIB;

  /** The word no generated text holds by chance. */
  static final String RARE = "flagword";

  /** Small files per file holding {@value #RARE}. */
  static final int RARE_EVERY = 100;

  private static final int FILES_PER_DIRECTORY = 100;

  /** What a search looks for. */
  public enum Query {
    /** {@value #RARE} as a literal. */
    LITERAL_FEW(RARE, false),
    /** A word about half of the lines hold, as a literal. */
    LITERAL_MANY("return", false),
    /** {@value #RARE} as a regex. */
    REGEX_FEW("flag\\w+d", true),
    /** That word as a regex. */
    REGEX_MANY("ret\\w+n", true);

    private final String text;
    private final boolean regex;

    Query(String text, boolean regex) {
      this.text = text;
      this.regex = regex;
    }

    /** Returns the request of this query over a folder, as the dialog builds it. */
    SearchRequest request(Path folder) {
      return new SearchRequest(
          text, regex, true, false, SearchScope.TREE, folder, "", "", "", false);
    }
  }

  private final Path root;

  private SearchTree(Path root) {
    this.root = root;
  }

  /**
   * Generates a tree.
   *
   * @param smallFiles the small files
   * @param largeFiles the large files
   * @param largeChars the chars of a large file
   * @return the tree
   * @throws IOException when a write fails; what was written is deleted
   */
  static SearchTree create(int smallFiles, int largeFiles, int largeChars) throws IOException {
    SearchTree tree = new SearchTree(Files.createTempDirectory("qcaret-search").toRealPath());
    boolean done = false;
    try {
      for (int i = 0; i < smallFiles; i++) {
        Path directory = tree.root.resolve("d" + i / FILES_PER_DIRECTORY);
        if (i % FILES_PER_DIRECTORY == 0) {
          Files.createDirectory(directory);
        }
        write(directory.resolve("f" + i + ".txt"), SMALL_CHARS, i, i % RARE_EVERY == 0);
      }
      Files.createDirectory(tree.large());
      for (int i = 0; i < largeFiles; i++) {
        write(tree.large().resolve("large" + i + ".txt"), largeChars, smallFiles + i, true);
      }
      done = true;
      return tree;
    } finally {
      if (!done) {
        tree.close();
      }
    }
  }

  private static void write(Path file, int chars, int seed, boolean rare) throws IOException {
    StringBuilder text = new StringBuilder(Corpus.ASCII.generate(chars, Workloads.SEED + seed));
    if (rare) {
      text.replace(chars / 2, chars / 2 + RARE.length(), RARE);
    }
    Files.writeString(file, text, StandardCharsets.US_ASCII);
  }

  /**
   * Returns the tree's real path.
   *
   * @return the root
   */
  Path root() {
    return root;
  }

  /**
   * Returns the directory of the large files.
   *
   * @return the directory
   */
  Path large() {
    return root.resolve("large");
  }

  /**
   * Returns a search's options at the default limits.
   *
   * @param query the query
   * @param budget charged for the reads
   * @return the options
   */
  FileSearch.Options options(Query query, MemoryBudget budget) {
    HyperSearch.Limits limits = HyperSearch.Limits.DEFAULTS;
    return new FileSearch.Options(
        query.request(root).query().compile(),
        filter(),
        limits.fallback(),
        limits.maxFileBytes(),
        limits.maxLineChars(),
        budget);
  }

  /**
   * Returns the filter of a search with no masks.
   *
   * @return the filter
   */
  static FileFilter filter() {
    return FileFilter.of("", "", "", false);
  }

  /** Deletes the tree. */
  @Override
  public void close() throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
