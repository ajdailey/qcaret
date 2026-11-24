package io.github.ajdailey.qcaret.ui.swt.platform;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Display;

/**
 * The files the system hands a launch as {@code SWT.OpenDocument} events instead of arguments, one
 * implementation per platform from {@link PlatformTraits#launchDocuments}: {@link
 * CocoaLaunchDocuments} on cocoa, where Finder's Open With and a Dock drop start QCaret that way;
 * {@link NativeLaunchDocuments} elsewhere. Call on the UI thread, or on the main thread before a
 * Display exists.
 */
public interface LaunchDocuments {
  /** The running platform's document events. */
  LaunchDocuments PLATFORM = PlatformTraits.PLATFORM.launchDocuments();

  /**
   * Returns the files this start's open-document events named, dispatching the queued events before
   * the first window.
   *
   * @param display the Display
   * @return the files in arrival order, empty when none arrived
   */
  List<Path> collect(Display display);

  /**
   * Returns the files a launch that hands off to a running instance was started with: on cocoa it
   * runs the wait; elsewhere none.
   *
   * @param wait runs a Display without a window until the events stop
   * @return the file names in arrival order, empty when none arrived
   */
  List<String> await(Wait wait);

  /** The wait before a handoff, which owns its Display and dispatching. */
  interface Wait {
    /**
     * Runs the wait.
     *
     * @return the file names in arrival order, empty when none arrived
     */
    List<String> run();
  }
}
