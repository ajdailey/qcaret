package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Sizes and positions a main window, one implementation per platform from {@link
 * PlatformTraits#windowPlacement}. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Position</th><th>Trim</th></tr>
 *   <tr><td>win32</td><td>{@link Win32WindowPlacement}</td><td>applied</td>
 *       <td>computed, then measured from the sized shell</td></tr>
 *   <tr><td>cocoa</td><td>{@link NativeWindowPlacement}</td><td>applied</td><td>computed</td></tr>
 *   <tr><td>GTK</td><td>{@link GtkWindowPlacement}</td><td>skipped on GTK 4 and under Wayland</td>
 *       <td>computed</td></tr>
 * </table>
 */
public interface WindowPlacement {
  /** The running platform's placement. */
  WindowPlacement PLATFORM = PlatformTraits.PLATFORM.windowPlacement();

  /**
   * Places a shell through a placer, which sizes it, and positions it when told to. win32's {@code
   * computeTrim} overstates the height once a menu bar is set, so there the placer runs again with
   * the trim measured from the sized shell when that differs.
   *
   * @param shell the shell
   * @param placer places the shell for a trim
   */
  void place(Shell shell, Placer placer);

  /** Places a shell for a trim. */
  interface Placer {
    /**
     * Sizes the shell, and moves it when {@code positioned}.
     *
     * @param trimWidth the trim's width
     * @param trimHeight the trim's height
     * @param positioned false where the window system ignores a position
     */
    void place(int trimWidth, int trimHeight, boolean positioned);
  }
}
