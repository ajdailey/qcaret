package io.github.ajdailey.qcaret.launcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

/** Event-loop and widget helpers for tests that drive the composed application. */
final class UiLoop {
  private static boolean screenLogged;

  private UiLoop() {}

  /**
   * Dispatches events until the condition holds or the bound passes; a repeating 20 ms timer wakes
   * {@code Display.sleep} so the condition is rechecked without other events.
   *
   * @return true when the condition held
   */
  static boolean runUntil(final Display display, BooleanSupplier condition, Duration bound) {
    logScreen(display);
    long deadline = System.nanoTime() + bound.toNanos();
    final boolean[] stop = new boolean[1];
    Runnable wake =
        new Runnable() {
          @Override
          public void run() {
            if (!stop[0] && !display.isDisposed()) {
              display.timerExec(20, this);
            }
          }
        };
    display.timerExec(20, wake);
    try {
      while (!condition.getAsBoolean()) {
        if (System.nanoTime() > deadline || display.isDisposed()) {
          return condition.getAsBoolean();
        }
        if (!display.readAndDispatch()) {
          display.sleep();
        }
      }
      return true;
    } finally {
      stop[0] = true;
    }
  }

  /** Returns the open shells with a title, dialogs included. */
  static List<Shell> shells(Display display, String title) {
    List<Shell> found = new ArrayList<Shell>();
    for (Shell shell : display.getShells()) {
      if (!shell.isDisposed() && title.equals(shell.getText())) {
        found.add(shell);
      }
    }
    return found;
  }

  /** Presses the button with a label; returns true once pressed. */
  static boolean press(Control root, String label) {
    if (root instanceof Button button && label.equals(button.getText())) {
      button.notifyListeners(SWT.Selection, new Event());
      return true;
    }
    if (root instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        if (press(child, label)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns the text of every label under a control, joined by newlines. */
  static String labels(Control root) {
    StringBuilder text = new StringBuilder();
    if (root instanceof Label label) {
      text.append(label.getText()).append('\n');
    }
    if (root instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        text.append(labels(child));
      }
    }
    return text.toString();
  }

  /**
   * Prints the primary monitor's bounds and client area once per test JVM, so a run's log shows the
   * screen its size-dependent tests had.
   */
  private static void logScreen(Display display) {
    if (screenLogged) {
      return;
    }
    screenLogged = true;
    Monitor primary = display.getPrimaryMonitor();
    if (primary != null) {
      System.out.println(
          "Screen: primary monitor "
              + primary.getBounds()
              + ", client area "
              + primary.getClientArea());
    }
  }
}
