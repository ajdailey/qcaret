package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

/**
 * Posts pointer clicks and key chords as a user's input on GTK: through {@link Display#post} on GTK
 * 3, and through XTest from {@code python3} with {@code libXtst} on GTK 4, where {@code
 * Display.post} fails. Skips the test where neither reaches the display. Posted pointer events end
 * the test JVM on cocoa and miss native geometry on win32, so callers run on GTK only.
 */
final class PostedInput {
  private static final Duration SETTLE = Duration.ofMillis(20);

  private static final String XTEST =
      """
      import ctypes, sys
      x11 = ctypes.CDLL("libX11.so.6")
      xtst = ctypes.CDLL("libXtst.so.6")
      x11.XOpenDisplay.restype = ctypes.c_void_p
      d = ctypes.c_void_p(x11.XOpenDisplay(None))
      args = sys.argv[1:]
      if args[0] == "click":
          xtst.XTestFakeMotionEvent(d, -1, int(args[1]), int(args[2]), 0)
          x11.XFlush(d)
          xtst.XTestFakeButtonEvent(d, 1, True, 0)
          xtst.XTestFakeButtonEvent(d, 1, False, 0)
      else:
          codes = [x11.XKeysymToKeycode(d, int(k, 16)) for k in args[1:]]
          for c in codes:
              xtst.XTestFakeKeyEvent(d, c, True, 0)
          for c in reversed(codes):
              xtst.XTestFakeKeyEvent(d, c, False, 0)
      x11.XFlush(d)
      x11.XCloseDisplay(d)
      """;

  private PostedInput() {}

  /** Skips the running test off GTK. */
  static void assumeGtk() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "posted input is checked on GTK only");
  }

  /**
   * Clicks the primary button at a display point.
   *
   * @param display the Display
   * @param at the point in display coordinates
   */
  static void click(Display display, Point at) {
    if (!gtk4()) {
      post(display, mouse(SWT.MouseMove, at, 0));
      post(display, mouse(SWT.MouseDown, at, 1));
      post(display, mouse(SWT.MouseUp, at, 1));
      return;
    }
    xtest(display, "click", String.valueOf(at.x), String.valueOf(at.y));
  }

  /**
   * Presses Ctrl, Shift and a lower-case letter together, then releases them.
   *
   * @param display the Display
   * @param letter the letter, {@code a} to {@code z}
   */
  static void ctrlShift(Display display, char letter) {
    if (!gtk4()) {
      post(display, key(SWT.KeyDown, SWT.CTRL, 0));
      post(display, key(SWT.KeyDown, SWT.SHIFT, 0));
      post(display, key(SWT.KeyDown, 0, letter));
      post(display, key(SWT.KeyUp, 0, letter));
      post(display, key(SWT.KeyUp, SWT.SHIFT, 0));
      post(display, key(SWT.KeyUp, SWT.CTRL, 0));
      return;
    }
    xtest(display, "keys", "ffe3", "ffe1", Integer.toHexString(letter));
  }

  private static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }

  private static Event mouse(int type, Point at, int button) {
    Event event = new Event();
    event.type = type;
    event.x = at.x;
    event.y = at.y;
    event.button = button;
    return event;
  }

  private static Event key(int type, int keyCode, int character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = (char) character;
    return event;
  }

  private static void post(Display display, Event event) {
    assertTrue(display.post(event), "Display.post failed");
    settle(display);
  }

  private static void xtest(Display display, String... args) {
    List<String> command = new ArrayList<String>(List.of("python3", "-I", "-c", XTEST));
    command.addAll(List.of(args));
    int exit;
    try {
      Process python =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      exit = python.waitFor();
    } catch (IOException e) {
      exit = -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    assumeTrue(exit == 0, "no python3 with libXtst for posted input under GTK 4");
    settle(display);
  }

  private static void settle(Display display) {
    final long until = System.nanoTime() + SETTLE.toNanos();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        Duration.ofSeconds(5));
  }
}
