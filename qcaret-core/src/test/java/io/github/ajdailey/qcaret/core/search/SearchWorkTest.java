package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounds that hold on any runner: the files a walk accepts after a cancel or after the room ends,
 * the chars a search reads after a cancel in each scan and in the matcher, and the memory reads
 * hold. Each file carries one match. A file holds a reader slot until its {@link
 * FileSearch.Sink#found} returns, so the counts below follow from {@link FileSearch#READERS}, not
 * from timing.
 */
class SearchWorkTest {
  private static final int FILES = 200;

  /** Bytes a read holds per file byte. */
  private static final int READ_BYTES = 5;

  private static final String LINE = "a line of mock text without the word\n";

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;

  /** Counts what a run reports. */
  private static class Counting implements FileSearch.Sink {
    final AtomicInteger found = new AtomicInteger();
    final AtomicInteger accepted = new AtomicInteger(-1);
    final List<String> skips = new CopyOnWriteArrayList<String>();

    @Override
    public int room() {
      return Integer.MAX_VALUE;
    }

    @Override
    public void found(FileSearch.FileHits hits) {
      found.incrementAndGet();
    }

    @Override
    public void skipped(Path file, FileSearch.Skip reason) {
      skips.add(file + " " + reason);
    }

    @Override
    public void progress(int searched, int total) {
      if (total >= 0) {
        accepted.set(total);
      }
    }
  }

  /** Counts char reads and cancels a token at one of them. */
  private static final class CountingChars implements CharSequence {
    private final String text;
    private final CancellationToken token;
    private final long cancelAt;
    final AtomicLong reads = new AtomicLong();

    CountingChars(String text, CancellationToken token, long cancelAt) {
      this.text = text;
      this.token = token;
      this.cancelAt = cancelAt;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if (reads.incrementAndGet() == cancelAt) {
        token.cancel();
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text;
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
    for (int i = 0; i < FILES; i++) {
      Files.writeString(
          temp.resolve("file" + i + ".txt"), "needle " + LINE, StandardCharsets.UTF_8);
    }
  }

  @AfterEach
  void close() {
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private FileSearch.Options options(MemoryBudget budget) {
    return new FileSearch.Options(
        new SearchQuery("needle", false, true, false).compile(),
        FileFilter.of("", "", "", false),
        StandardCharsets.UTF_8,
        1000,
        1_000_000,
        budget);
  }

  private void run(FileSearch.Sink sink, MemoryBudget budget, CancellationToken token) {
    FileSearch.run(workers, temp, true, Set.of(), options(budget), sink, token);
  }

  @Test
  void aCancelStopsTheWalkWithinTheReaders() {
    final CancellationToken token = new CancellationToken();
    Counting sink =
        new Counting() {
          @Override
          public void found(FileSearch.FileHits hits) {
            super.found(hits);
            token.cancel();
          }
        };
    run(sink, null, token);
    assertTrue(
        sink.accepted.get() <= FileSearch.READERS + 1, "files accepted: " + sink.accepted.get());
    assertTrue(sink.skips.isEmpty(), sink.skips.toString());
  }

  @Test
  void noRoomStopsTheWalkWithinTheReaders() {
    final int room = 10;
    Counting sink =
        new Counting() {
          @Override
          public int room() {
            return room - found.get();
          }
        };
    run(sink, null, new CancellationToken());
    assertTrue(
        sink.accepted.get() <= room + FileSearch.READERS, "files accepted: " + sink.accepted.get());
  }

  @Test
  void readsHoldFiveBytesPerFileByteForAtMostTheReaders() throws IOException {
    final long size = Files.size(temp.resolve("file0.txt"));
    final MemoryBudget budget = new MemoryBudget(1_000_000, 100);
    final AtomicLong least = new AtomicLong(Long.MAX_VALUE);
    final AtomicLong most = new AtomicLong();
    Counting sink =
        new Counting() {
          @Override
          public void found(FileSearch.FileHits hits) {
            super.found(hits);
            long held = budget.reserved();
            least.accumulateAndGet(held, Math::min);
            most.accumulateAndGet(held, Math::max);
          }
        };
    run(sink, budget, new CancellationToken());
    assertEquals(FILES, sink.found.get());
    assertTrue(least.get() >= size * READ_BYTES, "least held: " + least.get());
    assertTrue(most.get() <= size * READ_BYTES * FileSearch.READERS, "most held: " + most.get());
    assertEquals(0, budget.reserved());
  }

  /** Runs a search that cancels at a char read and returns the reads after it. */
  private static long readsAfterCancel(String regex, String text, long cancelAt) {
    final CancellationToken token = new CancellationToken();
    final CountingChars chars = new CountingChars(text, token, cancelAt);
    final TextMatcher matcher = new SearchQuery(regex, true, true, false).compile();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            TextSearch.search(matcher, chars, null, 100, Integer.MAX_VALUE, token);
          }
        });
    return chars.reads.get() - cancelAt;
  }

  private static String text() {
    return LINE.repeat((1 << 21) / LINE.length());
  }

  @Test
  void aCancelInTheLineStartScanStopsWithinOneBlock() {
    String text = text();
    long after = readsAfterCancel("zq[xy]", text, text.length() / 2);
    assertTrue(after <= TextSearch.SCAN_CHARS, "reads after the cancel: " + after);
  }

  @Test
  void aCancelInTheLongLineScanStopsWithinOneBlock() {
    String text = text();
    long after = readsAfterCancel("zq[xy]", text, text.length() + 1000L);
    assertTrue(after <= TextSearch.SCAN_CHARS, "reads after the cancel: " + after);
  }

  @Test
  void aCancelInTheMatcherStopsWithinOneCheckInterval() {
    String text = text();
    long after = readsAfterCancel("zq[xy]", text, 2L * text.length() + 1000L);
    assertTrue(after <= TextMatcher.CHECK_CHARS, "reads after the cancel: " + after);
  }

  @Test
  void aCancelInTheLiteralSearchStopsWithinOneCheckInterval() {
    String text = text();
    long after = readsAfterCancel("zqx[xy]", text, 2L * text.length() + 1000L);
    assertTrue(after <= TextMatcher.CHECK_CHARS, "reads after the cancel: " + after);
  }
}
