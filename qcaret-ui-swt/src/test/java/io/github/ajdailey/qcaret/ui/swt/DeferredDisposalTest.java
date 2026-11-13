package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link DeferredDisposal} hides at once and disposes in a later task. */
@Tag("swt")
class DeferredDisposalTest {
  /** Holds posted tasks until the test runs them. */
  private static final class Posted implements UiDispatcher {
    final List<Runnable> tasks = new ArrayList<Runnable>();

    @Override
    public void asyncExec(Runnable task) {
      tasks.add(task);
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      throw new AssertionError("no timers");
    }

    @Override
    public void cancelTimer(Runnable task) {
      throw new AssertionError("no timers");
    }

    @Override
    public boolean isUiThread() {
      return true;
    }

    void runAll() {
      while (!tasks.isEmpty()) {
        tasks.remove(0).run();
      }
    }
  }

  @Test
  void hidesNowAndDisposesInALaterTask() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      Posted ui = new Posted();
      DeferredDisposal disposal = new DeferredDisposal(ui);
      List<Composite> closed = new ArrayList<Composite>();
      for (int i = 0; i < 3; i++) {
        Composite c = new Composite(shell, SWT.NONE);
        new Composite(c, SWT.NONE);
        closed.add(c);
        disposal.dispose(c);
      }
      assertEquals(1, ui.tasks.size());
      assertEquals(3, disposal.pending());
      for (Composite c : closed) {
        assertFalse(c.isDisposed());
        assertFalse(c.getVisible());
      }
      ui.runAll();
      assertEquals(0, disposal.pending());
      for (Composite c : closed) {
        assertTrue(c.isDisposed());
      }
      assertFalse(shell.isDisposed());
    } finally {
      display.dispose();
    }
  }

  @Test
  void skipsAControlDisposedBeforeItsTurn() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      Posted ui = new Posted();
      DeferredDisposal disposal = new DeferredDisposal(ui);
      Composite early = new Composite(shell, SWT.NONE);
      Composite late = new Composite(shell, SWT.NONE);
      disposal.dispose(early);
      disposal.dispose(late);
      early.dispose();
      ui.runAll();
      assertTrue(late.isDisposed());
      disposal.dispose(early);
      assertEquals(0, disposal.pending());
      assertTrue(ui.tasks.isEmpty());
    } finally {
      display.dispose();
    }
  }
}
