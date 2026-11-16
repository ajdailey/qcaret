package io.github.ajdailey.qcaret.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.io.Checksums;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import io.github.ajdailey.qcaret.core.search.SearchQuery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cancel while a moved CPU step waits in the bulk queue ends each file operation with a {@code
 * CancellationException} before the step runs: load, save, search, checksum and the watch digest.
 * The only bulk thread is held, so the step cannot start before the cancel.
 */
class BulkStepCancelTest {
  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Thread.UncaughtExceptionHandler handler =
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable error) {
          failures.add(error);
        }
      };

  /** A file operation under test. */
  private interface Work {
    void run(CancellationToken token) throws IOException;
  }

  @Test
  void aLoadCanceledWhileItsDecodeIsQueued() throws Exception {
    final Path file = file("load.txt");
    cancelWhileQueued(
        new Work() {
          @Override
          public void run(CancellationToken token) throws IOException {
            DocumentFiles.load(file, LoadOptions.DEFAULTS, token);
          }
        });
  }

  @Test
  void aSaveCanceledWhileItsEncodeIsQueuedLeavesTheFile() throws Exception {
    final Path file = file("save.txt");
    byte[] before = Files.readAllBytes(file);
    final DocumentSnapshot snapshot =
        DocumentFiles.load(file, LoadOptions.DEFAULTS, new CancellationToken())
            .document()
            .snapshot();
    final Path target = temp.resolve("target.txt");
    Files.write(target, before);
    cancelWhileQueued(
        new Work() {
          @Override
          public void run(CancellationToken token) throws IOException {
            DocumentFiles.save(target, snapshot, null, token);
          }
        });
    assertArrayEquals(before, Files.readAllBytes(target));
  }

  @Test
  void aSearchCanceledWhileItsDecodeIsQueued() throws Exception {
    final Path file = file("search.txt");
    final FileSearch.Options options =
        new FileSearch.Options(
            new SearchQuery("needle", false, true, false).compile(),
            null,
            StandardCharsets.UTF_8,
            1 << 24,
            1_000_000,
            null);
    cancelWhileQueued(
        new Work() {
          @Override
          public void run(CancellationToken token) {
            FileSearch.searchFile(file, options, 10, token);
          }
        });
  }

  @Test
  void aChecksumCanceledWhileItsDigestIsQueued() throws Exception {
    final Path file = file("checksum.txt");
    cancelWhileQueued(
        new Work() {
          @Override
          public void run(CancellationToken token) throws IOException {
            Checksums.ofFile(
                file,
                Digests.sha256(),
                token,
                new LongConsumer() {
                  @Override
                  public void accept(long read) {}
                });
          }
        });
  }

  @Test
  void aWatchDigestCanceledWhileQueued() throws Exception {
    final Path file = file("watched.txt");
    cancelWhileQueued(
        new Work() {
          @Override
          public void run(CancellationToken token) throws IOException {
            Digests.of(file, token);
          }
        });
  }

  private Path file(String name) throws IOException {
    Path file = temp.resolve(name);
    Files.writeString(file, "mock text needle\n".repeat(1000), StandardCharsets.UTF_8);
    return file;
  }

  private void cancelWhileQueued(final Work work) throws Exception {
    Workers workers = new Workers(1, handler);
    CountDownLatch release = new CountDownLatch(1);
    final CancellationToken token = new CancellationToken();
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    try {
      WorkerKindsTest.occupyBulk(workers, release);
      Thread task =
          workers.start(
              Workers.Kind.IO,
              new Runnable() {
                @Override
                public void run() {
                  try {
                    work.run(token);
                  } catch (IOException | CancellationException e) {
                    thrown.set(e);
                  }
                }
              });
      WorkerKindsTest.awaitQueued(workers, 1);
      token.cancel();
      assertTrue(task.join(Duration.ofSeconds(10)), "the task ended while the pool was busy");
      assertTrue(thrown.get() instanceof CancellationException, String.valueOf(thrown.get()));
    } finally {
      release.countDown();
      assertTrue(workers.close(Duration.ofSeconds(10)));
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }
}
