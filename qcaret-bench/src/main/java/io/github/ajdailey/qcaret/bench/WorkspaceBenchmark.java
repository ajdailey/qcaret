package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.command.Fuzzy;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.workspace.DirectoryListing;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Workspace navigation on a generated tree in {@code java.io.tmpdir}: the Quick Open index walk
 * ({@link FileIndex#build}), one Workspace Browser directory of {@value
 * DirectoryListing#MAX_ENTRIES} entries ({@link DirectoryListing#read}), and a Quick Open query
 * scored with {@link Fuzzy} on each file name, then on the path where the name does not match, over
 * the whole index ({@code matchIndex}, the worker's share) and over the first {@value #SYNC_FILES}
 * paths ({@code matchSync}, the largest index matched on the UI thread). Ranking is not included.
 * Every file is empty; the whole tree is deleted at the end.
 */
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class WorkspaceBenchmark {
  /** Files under the indexed tree: 100 folders of 10 folders of 100 files. */
  static final int TREE_FILES = 100_000;

  /** {@code QuickOpen.SYNC_FILES}. */
  static final int SYNC_FILES = 2_000;

  private static final String QUERY = "mk5j";
  private static final String[] EXTENSIONS = {".java", ".md", ".xml", ".txt", ".properties"};

  /** The tree, the flat directory and the index of the tree. */
  @State(Scope.Benchmark)
  public static class Tree {
    /** Top folders of the indexed tree, each holding 1,000 files. */
    int modules = TREE_FILES / 1000;

    /** Files in the flat directory. */
    int flatFiles = DirectoryListing.MAX_ENTRIES;

    Path root;
    Path tree;
    Path flat;
    String[] paths;
    final WalkRules rules = WalkRules.of(".git;.svn;.hg");
    final MemoryBudget budget = MemoryBudget.fromRuntime();

    /**
     * Writes the tree and indexes it.
     *
     * @throws IOException when a file cannot be created
     */
    @Setup
    public void create() throws IOException {
      root = Files.createTempDirectory("qcaret-bench-workspace");
      tree = Files.createDirectory(root.resolve("tree"));
      flat = Files.createDirectory(root.resolve("flat"));
      int n = 0;
      for (int a = 0; a < modules; a++) {
        Path top = Files.createDirectory(tree.resolve("module" + a));
        for (int b = 0; b < 10; b++) {
          Path folder = Files.createDirectory(top.resolve("pkg" + b));
          for (int c = 0; c < 100; c++) {
            Files.createFile(folder.resolve(name(n++)));
          }
        }
      }
      for (int i = 0; i < flatFiles; i++) {
        Files.createFile(flat.resolve(name(i)));
      }
      FileIndex index =
          FileIndex.build(tree, rules, FileIndex.MAX_FILES, budget, new CancellationToken());
      paths = new String[index.size()];
      for (int i = 0; i < paths.length; i++) {
        paths[i] = index.path(i);
      }
      budget.release(index.charged());
      if (paths.length != modules * 1000) {
        throw new IllegalStateException("indexed " + paths.length);
      }
    }

    private static String name(int n) {
      return "Mock" + Integer.toString(n * 7919, 36) + "Item" + EXTENSIONS[n % EXTENSIONS.length];
    }

    /**
     * Deletes the tree.
     *
     * @throws IOException when a file cannot be deleted
     */
    @TearDown
    public void delete() throws IOException {
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
            public FileVisitResult postVisitDirectory(Path dir, IOException error)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  /**
   * Builds the Quick Open index of the tree.
   *
   * @param s the tree
   * @return the file count
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int indexBuild(Tree s) {
    FileIndex index =
        FileIndex.build(s.tree, s.rules, FileIndex.MAX_FILES, s.budget, new CancellationToken());
    s.budget.release(index.charged());
    return index.size();
  }

  /**
   * Lists the flat directory.
   *
   * @param s the tree
   * @return the entry count
   * @throws IOException when the directory cannot be read
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int listFlat(Tree s) throws IOException {
    return DirectoryListing.read(s.flat, s.rules, new CancellationToken()).entries().size();
  }

  /**
   * Scores the query over every indexed path.
   *
   * @param s the tree
   * @return the matches
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int matchIndex(Tree s) {
    return match(s.paths, s.paths.length);
  }

  /**
   * Scores the query over the first {@value #SYNC_FILES} paths.
   *
   * @param s the tree
   * @return the matches
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int matchSync(Tree s) {
    return match(s.paths, Math.min(SYNC_FILES, s.paths.length));
  }

  private static int match(String[] paths, int count) {
    int matches = 0;
    for (int i = 0; i < count; i++) {
      String path = paths[i];
      String name = path.substring(path.lastIndexOf('/') + 1);
      if (Fuzzy.score(QUERY, name) >= 0 || Fuzzy.score(QUERY, path) >= 0) {
        matches++;
      }
    }
    return matches;
  }
}
