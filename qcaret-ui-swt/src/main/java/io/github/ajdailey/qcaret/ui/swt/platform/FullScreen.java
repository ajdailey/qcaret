package io.github.ajdailey.qcaret.ui.swt.platform;

/**
 * A main window's View > Full Screen state, not saved, one implementation per platform from {@link
 * PlatformTraits#fullScreen}. While {@link #on}, the window's tracked bounds stay those from
 * before. Confined to the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>State</th></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaFullScreen}</td><td>the shell's, in a space of its own</td>
 *   </tr>
 *   <tr><td>win32, GTK</td><td>{@link NativeFullScreen}</td><td>the last request</td></tr>
 * </table>
 */
public interface FullScreen {
  /**
   * Reports whether the window fills the screen now.
   *
   * @return true while full screen
   */
  boolean on();

  /**
   * Reports the state View > Full Screen shows: a cocoa request still waiting for the shell, else
   * {@link #on}.
   *
   * @return true when full screen is on or requested
   */
  boolean shown();

  /**
   * Fills the screen with the window, or returns it to its bounds.
   *
   * @param value true to fill the screen
   */
  void set(boolean value);

  /** Records the maximized state of a window that is not full screen. */
  void track();

  /**
   * Reports whether the window is maximized, or was before full screen.
   *
   * @return the state
   */
  boolean maximized();
}
