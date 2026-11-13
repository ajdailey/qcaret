package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker results that read only what the UI thread handed them, and that reach the UI thread when
 * the worker fails.
 */
class WorkerResultsTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws Exception {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  /**
   * The count runs after the encoding changed: every CPU worker is held until then. It reports the
   * encoding the document had when the count was asked for, read on the UI thread.
   */
  @Test
  void statisticsCountInTheEncodingTakenWithTheSnapshot() throws Exception {
    harness.open(Files.writeString(temp.resolve("a.txt"), "one two\n", StandardCharsets.UTF_8));
    final CountDownLatch busy = new CountDownLatch(harness.workers.cpuThreads());
    final CountDownLatch release = new CountDownLatch(1);
    for (int i = 0; i < harness.workers.cpuThreads(); i++) {
      harness.workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              busy.countDown();
              try {
                release.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          });
    }
    final FakeView.Statistics shown;
    try {
      assertTrue(busy.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "workers held");
      harness.application.execute(CommandId.DOCUMENT_STATISTICS);
      shown = harness.view.statistics;
      harness
          .application
          .documents()
          .activeDocument()
          .setEncoding(new TextEncoding(StandardCharsets.UTF_16LE, false));
    } finally {
      release.countDown();
    }
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.report != null || shown.message != null;
          }
        },
        "counted");
    assertEquals("UTF-8", shown.report.encoding());
    assertEquals(8, shown.report.document().bytes());
  }

  /** THR-2: the search posts from {@code finally}, so the bar stops showing it as running. */
  @Test
  void aFindFailingOnItsWorkerEndsAndReports() throws Exception {
    harness.open(Files.writeString(temp.resolve("f.txt"), "alpha\n", StandardCharsets.UTF_8));
    FindController find = harness.application.find();
    final AtomicReference<FindState> last = new AtomicReference<FindState>();
    find.addListener(
        new FindListener() {
          @Override
          public void findStateChanged(FindState state) {
            last.set(state);
          }

          @Override
          public void focusRequested() {
            // Not asserted.
          }
        });
    final AtomicBoolean once = new AtomicBoolean(true);
    find.setBeforeFind(
        new Runnable() {
          @Override
          public void run() {
            if (once.compareAndSet(true, false)) {
              throw new IllegalStateException("mock failure");
            }
          }
        });
    harness.application.execute(CommandId.FIND);
    find.setText("alpha");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return last.get() != null && !last.get().running() && !harness.failures.isEmpty();
          }
        },
        "the search ended and its failure reached the handler");
    assertEquals(UiText.FIND_FAILED.text(), last.get().notice());
    assertTrue(last.get().error());
    assertEquals(1, harness.failures.size(), harness.failures.toString());
    assertEquals("mock failure", harness.failures.get(0).getMessage());
    harness.failures.clear();
    find.findNext();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !last.get().running() && !last.get().error();
          }
        },
        "the next search ran");
    assertFalse(last.get().running());
    assertEquals(List.of(), harness.failures);
  }
}
