package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Walks a generated tree: masks, excluded and hidden directories, the non-recursive scope, files
 * left to open documents, and the binary, too-large and memory skips. Each found file carries its
 * encoding, stamp and SHA-256, and the memory reserved for reads returns to the budget.
 */
class FileSearchTest {
  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;

  /** Collects what a run reports. */
  private static final class Collected implements FileSearch.Sink {
    final Map<String, FileSearch.FileHits> found =
        Collections.synchronizedMap(new TreeMap<String, FileSearch.FileHits>());
    final Map<String, FileSearch.Skip> skipped = new ConcurrentHashMap<String, FileSearch.Skip>();
    final AtomicInteger total = new AtomicInteger(-2);
    final Path root;
    int room = Integer.MAX_VALUE;

    Collected(Path root) {
      this.root = root;
    }

    @Override
    public int room() {
      return room;
    }

    @Override
    public void found(FileSearch.FileHits hits) {
      found.put(name(hits.path()), hits);
    }

    @Override
    public void skipped(Path file, FileSearch.Skip reason) {
      skipped.put(name(file), reason);
    }

    @Override
    public void progress(int searched, int all) {
      if (all >= 0) {
        total.set(all);
      }
    }

    private String name(Path file) {
      return root.relativize(file).toString().replace('\\', '/');
    }
  }

  @BeforeEach
  void create() throws IOException {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    write("a.txt", "needle one\nneedle two\n");
    write("skip.log", "needle\n");
    write("sub/b.java", "class B { // needle\n}\n");
    write("sub/deep/c.txt", "no match\n");
    write(".hidden/d.txt", "needle\n");
    write(".git/e.txt", "needle\n");
    write("sub/.f.txt", "needle\n");
    Files.write(temp.resolve("bin.dat"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 1, 2});
    write("open.txt", "needle on disk\n");
    write("large.txt", "needle\n" + "x".repeat(5000));
  }

  @AfterEach
  void close() {
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void write(String name, String text) throws IOException {
    Path file = temp.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private Collected run(boolean recursive, FileFilter filter, MemoryBudget budget) {
    Collected sink = new Collected(temp);
    FileSearch.run(
        workers,
        temp,
        recursive,
        Set.of(temp.resolve("open.txt").toAbsolutePath().normalize()),
        new FileSearch.Options(
            new SearchQuery("needle", false, true, false).compile(),
            filter,
            StandardCharsets.UTF_8,
            1000,
            1_000_000,
            budget),
        sink,
        new CancellationToken());
    return sink;
  }

  @Test
  void aRecursiveWalkSkipsHiddenExcludedOpenBinaryAndLargeFiles() throws IOException {
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    Collected sink =
        run(
            true,
            FileFilter.of("", "*.log", FileFilter.DEFAULT_EXCLUDED_DIRECTORIES, false),
            budget);
    assertEquals(List.of("a.txt", "sub/b.java"), new ArrayList<String>(sink.found.keySet()));
    assertEquals(
        Map.of("bin.dat", FileSearch.Skip.BINARY, "large.txt", FileSearch.Skip.TOO_LARGE),
        sink.skipped);
    assertEquals(5, sink.total.get());
    FileSearch.FileHits a = sink.found.get("a.txt");
    assertEquals(2, a.outcome().hits().size());
    assertEquals(StandardCharsets.UTF_8, a.encoding().charset());
    assertEquals(FileStamp.read(temp.resolve("a.txt")), a.stamp());
    assertArrayEquals(FileSearch.digest(Files.readAllBytes(temp.resolve("a.txt"))), a.digest());
    assertEquals(0, budget.reserved());
  }

  @Test
  void hiddenFilesAndMasksAndTheTopLevelOnly() {
    Collected hidden = run(true, FileFilter.of("*.txt", "", ".git", true), null);
    assertEquals(
        List.of(".hidden/d.txt", "a.txt", "sub/.f.txt"),
        new ArrayList<String>(hidden.found.keySet()));
    Collected top = run(false, FileFilter.of("", "", "", false), null);
    assertEquals(List.of("a.txt", "skip.log"), new ArrayList<String>(top.found.keySet()));
  }

  @Test
  void aRefusedReadReservationSkipsTheFile() {
    MemoryBudget budget = new MemoryBudget(100, 100);
    Collected sink = run(false, FileFilter.of("a.txt", "", "", false), budget);
    assertTrue(sink.found.isEmpty());
    assertEquals(Map.of("a.txt", FileSearch.Skip.NO_MEMORY), sink.skipped);
  }

  @Test
  void noRoomEndsTheWalk() {
    Collected sink = new Collected(temp);
    sink.room = 0;
    FileSearch.run(
        workers,
        temp,
        true,
        Set.of(),
        new FileSearch.Options(
            new SearchQuery("needle", false, true, false).compile(),
            FileFilter.of("", "", "", true),
            StandardCharsets.UTF_8,
            1000,
            1_000_000,
            null),
        sink,
        new CancellationToken());
    assertTrue(sink.found.isEmpty());
  }
}
