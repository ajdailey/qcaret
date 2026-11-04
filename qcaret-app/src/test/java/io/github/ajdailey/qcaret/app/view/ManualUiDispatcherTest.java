package io.github.ajdailey.qcaret.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManualUiDispatcherTest {
  @Test
  void runsInOrderAndDropsAfterDispose() {
    final ManualUiDispatcher ui = new ManualUiDispatcher();
    final List<String> ran = new ArrayList<String>();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran.add("a");
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    ran.add("c");
                  }
                });
          }
        });
    ui.timerExec(
        50,
        new Runnable() {
          @Override
          public void run() {
            ran.add("b");
          }
        });
    assertTrue(ui.isUiThread());
    assertEquals(3, ui.runPending());
    assertEquals(List.of("a", "b", "c"), ran);
    ui.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran.add("late");
          }
        });
    assertEquals(0, ui.runPending());
  }

  @Test
  void checkUiThreadFailsOffTheTestThread() throws Exception {
    final ManualUiDispatcher ui = new ManualUiDispatcher();
    ui.checkUiThread();
    final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    Thread worker =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  ui.checkUiThread();
                } catch (IllegalStateException e) {
                  failure.set(e);
                }
              }
            },
            "mock-worker");
    worker.start();
    worker.join(10_000);
    assertTrue(failure.get() instanceof IllegalStateException, String.valueOf(failure.get()));
    assertTrue(failure.get().getMessage().contains("mock-worker"));
  }
}
