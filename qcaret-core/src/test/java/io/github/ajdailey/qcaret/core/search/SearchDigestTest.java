package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SHA-256 of a searched file: computed only when its outcome has hits, skipped lines or a regex
 * failure, counted in {@link FileSearch.Stage#DIGEST} blocks through {@link FileSearch#setProbe},
 * and equal to the digest of the whole file for files past every block size.
 */
class SearchDigestTest {
  /** Least blocks in each file. */
  private static final int BLOCKS = 20;

  private static final int MAX_LINE = 1000;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Map<Path, AtomicInteger> blocks = new ConcurrentHashMap<Path, AtomicInteger>();
  private Workers workers;

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
    write("hit.txt", "mock text\r\n", "needle\n");
    write("miss.txt", "mock text\r\n", "");
    write("long.txt", "mock text\n", "x".repeat(MAX_LINE + 1) + "\r");
    FileSearch.setProbe(
        new FileSearch.Probe() {
          @Override
          public void block(Path file, FileSearch.Stage stage) {
            if (stage == FileSearch.Stage.DIGEST) {
              blocks.putIfAbsent(file, new AtomicInteger());
              blocks.get(file).incrementAndGet();
            }
          }
        });
  }

  @AfterEach
  void close() {
    FileSearch.setProbe(null);
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Lines past {@link #BLOCKS} blocks with {@code middle} in the middle. */
  private void write(String name, String line, String middle) throws IOException {
    String half = line.repeat(BLOCKS * FileSearch.BLOCK_BYTES / line.length() / 2 + 1);
    Files.writeString(temp.resolve(name), half + middle + half, StandardCharsets.UTF_8);
  }

  private static FileSearch.Options options() {
    return new FileSearch.Options(
        new SearchQuery("needle", false, true, false).compile(),
        FileFilter.of("", "", "", false),
        StandardCharsets.UTF_8,
        64L << 20,
        MAX_LINE,
        null);
  }

  private int blocks(String name) {
    AtomicInteger n = blocks.get(temp.resolve(name));
    return n == null ? 0 : n.get();
  }

  private void assertWholeFileDigest(String name, byte[] digest) throws IOException {
    byte[] bytes = Files.readAllBytes(temp.resolve(name));
    assertArrayEquals(FileSearch.digest(bytes), digest, name);
    assertEquals((bytes.length - 1) / FileSearch.BLOCK_BYTES + 1, blocks(name), name);
  }

  @Test
  void aWalkDigestsOnlyTheFilesItReports() throws IOException {
    final Map<String, FileSearch.FileHits> found =
        new ConcurrentHashMap<String, FileSearch.FileHits>();
    FileSearch.run(
        workers,
        temp,
        true,
        Set.of(),
        options(),
        new FileSearch.Sink() {
          @Override
          public int room() {
            return Integer.MAX_VALUE;
          }

          @Override
          public void found(FileSearch.FileHits hits) {
            found.put(hits.path().getFileName().toString(), hits);
          }

          @Override
          public void skipped(Path file, FileSearch.Skip reason) {
            failures.add(new AssertionError(file + " " + reason));
          }

          @Override
          public void progress(int searched, int total) {}
        },
        new CancellationToken());
    assertEquals(Set.of("hit.txt", "long.txt"), found.keySet());
    assertEquals(1, found.get("hit.txt").outcome().hits().size());
    assertEquals(1, found.get("long.txt").outcome().skippedLines());
    assertWholeFileDigest("hit.txt", found.get("hit.txt").digest());
    assertWholeFileDigest("long.txt", found.get("long.txt").digest());
    assertEquals(0, blocks("miss.txt"));
  }

  @Test
  void aSingleFileWithoutHitsHasNoDigest() throws IOException {
    FileSearch.Single miss =
        FileSearch.searchFile(temp.resolve("miss.txt"), options(), 100, new CancellationToken());
    assertNotNull(miss.hits());
    assertNull(miss.hits().digest());
    assertEquals(0, blocks("miss.txt"));
    FileSearch.Single hit =
        FileSearch.searchFile(temp.resolve("hit.txt"), options(), 100, new CancellationToken());
    assertWholeFileDigest("hit.txt", hit.hits().digest());
  }
}
