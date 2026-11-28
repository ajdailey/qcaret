package io.github.ajdailey.qcaret.ui.swt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A paint wait posts its task after a paint, or after the timeout for a hidden control. */
@Tag("swt")
class PaintWaitSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private Display display;
  private Shell shell;
  private Canvas canvas;
  private final int[] runs = new int[1];
  private final int[] paints = new int[1];

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
    canvas = new Canvas(shell, SWT.NONE);
    canvas.setBounds(0, 0, 200, 100);
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paints[0]++;
          }
        });
    shell.setSize(240, 160);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private final Runnable task =
      new Runnable() {
        @Override
        public void run() {
          runs[0]++;
        }
      };

  private boolean ran() {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return runs[0] > 0;
          }
        },
        BOUND);
  }

  @Test
  void aPaintEndsTheWait() {
    shell.open();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return paints[0] > 0;
          }
        },
        BOUND);
    // A timeout longer than the bound: only the paint can end the wait.
    PaintWait.start(canvas, new SwtUiDispatcher(display), 60_000, task);
    canvas.redraw();
    assertTrue(ran(), "the task runs after the paint");
    assertEquals(1, runs[0]);
  }

  @Test
  void aHiddenControlEndsTheWaitAtTheTimeout() {
    PaintWait.start(canvas, new SwtUiDispatcher(display), 50, task);
    assertTrue(ran(), "the task runs after the timeout");
    assertEquals(1, runs[0]);
  }
}
