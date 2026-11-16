package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file search's decode runs on the bulk pool: one {@code IO} task per core whose decode spins
 * (through {@link FileSearch#setProbe}) leaves a task of every kind runnable. With the decode back
 * on the {@code IO} virtual thread, the virtual kinds wait for the spinners.
 */
class SearchStepsTest {
  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Thread.UncaughtExceptionHandler handler =
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable error) {
          failures.add(error);
        }
      };

  @AfterEach
  void clearProbe() {
    FileSearch.setProbe(null);
  }

  @Test
  void oneSpinningDecodePerCoreLeavesEveryKindRunnable() throws Exception {
    int cores = Runtime.getRuntime().availableProcessors();
    final AtomicBoolean spin = new AtomicBoolean(true);
    final CountDownLatch spinners = new CountDownLatch(cores);
    final Path root = temp.toAbsolutePath();
    FileSearch.setProbe(
        new FileSearch.Probe() {
          @Override
          public void block(Path file, FileSearch.Stage stage) {
            if (stage == FileSearch.Stage.DECODE && file.startsWith(root)) {
              spinners.countDown();
              while (spin.get()) {
                Thread.onSpinWait();
              }
            }
          }
        });
    final FileSearch.Options options =
        new FileSearch.Options(
            new SearchQuery("needle", false, true, false).compile(),
            null,
            StandardCharsets.UTF_8,
            1 << 20,
            1_000_000,
            null);
    Workers workers = new Workers(cores, handler);
    try {
      for (int i = 0; i < cores; i++) {
        final Path file = root.resolve("file" + i + ".txt");
        Files.writeString(file, "mock text needle\n", StandardCharsets.UTF_8);
        workers.start(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                FileSearch.searchFile(file, options, 10, new CancellationToken());
              }
            });
      }
      assertTrue(spinners.await(30, TimeUnit.SECONDS), "spinners started");
      Workers.Kind[] kinds = Workers.Kind.values();
      final CountDownLatch ran = new CountDownLatch(kinds.length);
      for (Workers.Kind kind : kinds) {
        workers.start(
            kind,
            new Runnable() {
              @Override
              public void run() {
                ran.countDown();
              }
            });
      }
      assertTrue(ran.await(30, TimeUnit.SECONDS), ran.getCount() + " kinds did not run");
    } finally {
      spin.set(false);
      assertTrue(workers.close(Duration.ofSeconds(10)));
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }
}
