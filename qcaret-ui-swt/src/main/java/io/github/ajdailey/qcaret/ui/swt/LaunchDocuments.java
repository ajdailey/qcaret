package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * opens the files as a start with file arguments does. Later events go to {@link AppMenu}.
 */
public final class LaunchDocuments implements Listener {
  /** The most event-loop passes {@link #collect} runs. */
  static final int PASSES = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(LaunchDocuments.class);

  private final List<Path> paths = new ArrayList<Path>();

  LaunchDocuments() {}

  /**
   * Dispatches the queued events, up to {@value #PASSES}, and returns the files the open-document
   * events among them named. Call on the UI thread before {@link AppMenu#hook}.
   *
   * @param display the Display
   * @return the files in arrival order, empty when none arrived
   */
  public static List<Path> collect(Display display) {
    return collect(display, new LaunchDocuments());
  }

  /**
   * Dispatches the queued events with a given listener; see {@link #collect(Display)}.
   *
   * @param display the Display
   * @param launch the listener
   * @return the files
   */
  static List<Path> collect(Display display, LaunchDocuments launch) {
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
   * Keeps an event's file; null, an empty name and a name that does not parse are skipped, the last
   * logged.
   *
   * @param event the event
   */
  @Override
  public void handleEvent(Event event) {
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
