package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.watch.FileCheck;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wait for a changed file to go quiet, with a wait long enough to pin the order of events. */
class ExternalChangesTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final long QUIET_MILLIS = 2000;
  private static final long POLL_MILLIS = 60_000;
  private static final long CHECK_MILLIS = 300;

  @TempDir Path temp;

  /**
   * Another program's truncate and write are two directory events, and the check between them reads
   * 0 bytes. Only the bytes after the write may reach the listener.
   */
  @Test
  void aTruncateAndALaterWriteReportOneChange() throws Exception {
    Path file = Files.writeString(temp.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
    FileBaseline baseline = FileCheck.read(file, new CancellationToken());
    final List<Long> sizes = new ArrayList<Long>();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    try (Workers workers = new Workers(1, new FailOnError())) {
      ExternalChanges changes =
          new ExternalChanges(
              workers,
              ui,
              POLL_MILLIS,
              QUIET_MILLIS,
              new ExternalChanges.Listener() {
                @Override
                public void found(ExternalChanges.Watch watch, FileCheck.Result result) {
                  sizes.add(result.current().stamp().size());
                }
              });
      changes.watch(file, baseline);
      Files.write(file, new byte[0]);
      changes.checkAll();
      Thread.sleep(CHECK_MILLIS);
      Files.writeString(file, "theirs\n", StandardCharsets.UTF_8);
      assertTrue(
          ui.runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return !sizes.isEmpty();
                }
              },
              BOUND));
      changes.close();
    }
    assertEquals(List.of(7L), sizes);
  }

  private static final class FailOnError implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread thread, Throwable error) {
      throw new AssertionError(error);
    }
  }
}
