package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Sizes and positions a main window, one implementation per platform from {@link
 * PlatformTraits#windowPlacement}. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Position</th><th>Trim</th><th>After open</th></tr>
 *   <tr><td>win32</td><td>{@link Win32WindowPlacement}</td>
 *       <td>applied, then fitted into the monitor's work area</td>
 *       <td>computed, then measured from the sized shell</td><td>nothing</td></tr>
 *   <tr><td>cocoa</td><td>{@link NativeWindowPlacement}</td><td>applied</td><td>computed</td>
 *       <td>nothing</td></tr>
 *   <tr><td>GTK 3</td><td>{@link GtkWindowPlacement}</td><td>skipped under Wayland</td>
 *       <td>computed</td><td>nothing</td></tr>
 *   <tr><td>GTK 4</td><td>{@link Gtk4WindowPlacement}</td><td>skipped</td><td>computed</td>
 *       <td>maximizes again</td></tr>
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

  /**
   * Runs after {@code Shell.open}. GTK 4 drops a maximize requested before the shell maps, so its
   * class maximizes a shell that should be maximized and opened restored; the others do nothing.
   *
   * @param shell the opened shell
   * @param maximized {@code getMaximized()} read before {@code open}
   */
  void afterOpen(Shell shell, boolean maximized);

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
