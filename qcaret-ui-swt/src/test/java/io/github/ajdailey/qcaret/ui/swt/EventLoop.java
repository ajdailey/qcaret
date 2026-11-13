package io.github.ajdailey.qcaret.ui.swt;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

/** Drives an SWT event loop in tests until a condition holds, with no fixed sleep. */
public final class EventLoop {
  private static boolean screenLogged;

  private EventLoop() {}

  /**
   * Dispatches events until the condition holds or the bound passes. A repeating 20 ms timer wakes
   * {@code Display.sleep} so the condition is rechecked without other events.
   *
   * @param display the Display, on the calling thread
   * @param condition checked between events
   * @param bound the longest wait
   * @return true when the condition held
   */
  public static boolean runUntil(final Display display, BooleanSupplier condition, Duration bound) {
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
