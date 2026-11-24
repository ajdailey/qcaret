package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The files the system hands a cold start as {@code SWT.OpenDocument} events: on macOS, Finder's
 * Open With and a drop on the Dock icon start QCaret without arguments and send the files in the
 * first event-loop passes. {@link #collect} runs those passes before the first window, so the start
 * opens the files as a start with file arguments does. Later events go to {@code AppMenu}. A launch
 * that hands its files to a running instance waits for the events with {@link #await}.
 */
public final class DocumentEventLoop implements Listener {
  /** The most event-loop passes {@link #collect} runs. */
  static final int PASSES = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(DocumentEventLoop.class);

  /** The longest {@link #await} waits for the first event; unmeasured until a Mac gate. */
  static final long FIRST_MILLIS = 1000;

  /** The quiet time after an event that ends {@link #await}; unmeasured until a Mac gate. */
  static final long QUIET_MILLIS = 200;

  private final List<Path> paths = new ArrayList<Path>();
  private final LongSupplier clock;
  private boolean arrived;
  private long lastNanos;

  DocumentEventLoop() {
    this(System::nanoTime);
  }

  /**
   * Creates the listener.
   *
   * @param clock nanoseconds for event arrival times and {@link #await}
   */
  DocumentEventLoop(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Dispatches the queued events, up to {@value #PASSES}, and returns the files the open-document
   * events among them named. Call on the UI thread before {@code AppMenu.hook}.
   *
   * @param display the Display
   * @return the files in arrival order, empty when none arrived
   */
  static List<Path> collect(Display display) {
    return collect(display, new DocumentEventLoop());
  }

  /**
   * Dispatches the queued events with a given listener; see {@link #collect(Display)}.
   *
   * @param display the Display
   * @param launch the listener
   * @return the files
   */
  static List<Path> collect(Display display, DocumentEventLoop launch) {
    display.addListener(SWT.OpenDocument, launch);
    try {
      int passes = 0;
      while (passes < PASSES && display.readAndDispatch()) {
        passes++;
      }
    } finally {
      display.removeListener(SWT.OpenDocument, launch);
    }
    return List.copyOf(launch.paths);
  }

  /**
   * Runs the event loop until events have arrived and {@value #QUIET_MILLIS} ms pass without
   * another, or {@value #FIRST_MILLIS} ms pass without one, and returns the files the open-document
   * events named. Call on the UI thread of a Display that shows no window.
   *
   * @param display the Display
   * @param ui runs the wake timer on the Display
   * @return the files in arrival order, empty when none arrived
   */
  public static List<Path> await(Display display, UiDispatcher ui) {
    return await(display, ui, new DocumentEventLoop());
  }

  /**
   * Waits with a given listener; see {@link #await(Display, UiDispatcher)}.
   *
   * @param display the Display
   * @param ui runs the wake timer on the Display
   * @param launch the listener, whose clock times the wait
   * @return the files
   */
  static List<Path> await(Display display, UiDispatcher ui, DocumentEventLoop launch) {
    Runnable wake =
        new Runnable() {
          @Override
          public void run() {
            // Ends display.sleep so the loop reads the clock.
          }
        };
    display.addListener(SWT.OpenDocument, launch);
    try {
      long start = launch.clock.getAsLong();
      while (true) {
        long left = launch.remainingMillis(start);
        if (left <= 0) {
          break;
        }
        if (!display.readAndDispatch()) {
          ui.timerExec((int) left, wake);
          display.sleep();
        }
      }
    } finally {
      ui.cancelTimer(wake);
      display.removeListener(SWT.OpenDocument, launch);
    }
    return List.copyOf(launch.paths);
  }

  /**
   * Returns the milliseconds {@link #await} still waits, rounded up, or 0 when it stops.
   *
   * @param startNanos the clock when the wait began
   * @return the milliseconds left
   */
  long remainingMillis(long startNanos) {
    long end =
        arrived
            ? lastNanos + TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS)
            : startNanos + TimeUnit.MILLISECONDS.toNanos(FIRST_MILLIS);
    long left = end - clock.getAsLong();
    return left <= 0
        ? 0
        : TimeUnit.NANOSECONDS.toMillis(left + TimeUnit.MILLISECONDS.toNanos(1) - 1);
  }

  /**
   * Keeps an event's file and its arrival time; null, an empty name and a name that does not parse
   * are skipped, the last logged.
   *
   * @param event the event
   */
  @Override
  public void handleEvent(Event event) {
    arrived = true;
    lastNanos = clock.getAsLong();
    String name = event.text;
    if (name == null || name.isEmpty()) {
      return;
    }
    try {
      paths.add(Path.of(name));
    } catch (InvalidPathException e) {
      LOG.warn("Cannot open {}: {}", LogText.oneLine(name), LogText.oneLine(e.getMessage()));
    }
  }
}
