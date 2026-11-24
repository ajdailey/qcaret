package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where the toolkit and the embedded browser keep their files, one implementation per system from
 * {@link OsTraits#fileConfinement}. Call before the Display exists.
 *
 * <table>
 *   <caption>Implementation per system</caption>
 *   <tr><th>System</th><th>Class</th><th>Home</th><th>Browser data</th><th>Guide</th></tr>
 *   <tr><td>Linux</td><td>{@link LinuxFileConfinement}</td><td>the settings directory in
 *       explicit and portable mode</td><td>not placed</td><td>outside the process there</td></tr>
 *   <tr><td>Windows</td><td>{@link WindowsFileConfinement}</td><td>kept</td>
 *       <td>{@code <settings>\cache\webview2}</td><td>embedded</td></tr>
 *   <tr><td>macOS</td><td>{@link MacFileConfinement}</td><td>kept</td><td>not placed</td>
 *       <td>embedded</td></tr>
 * </table>
 */
public interface FileConfinement {
  /** The SWT property naming the WebView2 user data folder. */
  String EDGE_DATA_DIR = "org.eclipse.swt.browser.EdgeDataDir";

  /** System property keeping the user's home folder once {@code user.home} moves. */
  String HOME_FOLDER = "qcaret.userHome";

  /**
   * Keeps the files the toolkit writes under {@code user.home} in the settings directory: on Linux,
   * in explicit and portable mode, {@code user.home} becomes the settings directory, so GTK SWT
   * writes its {@code .swt/trims.prefs} there, and {@link #HOME_FOLDER} keeps the first value for
   * the workspace browser's Home.
   *
   * @param perUser true in per-user mode
   * @param settings the settings directory
   */
  void confineHome(boolean perUser, Path settings);

  /**
   * Places the embedded browser's user data inside the settings directory: on Windows, where the
   * browser is WebView2, sets {@link #EDGE_DATA_DIR}, in every mode.
   *
   * @param data gives the folder
   * @throws IOException when the folder cannot be resolved
   */
  void placeBrowserData(BrowserData data) throws IOException;

  /**
   * Reports whether the user guide may open in an embedded browser: not on Linux in explicit and
   * portable mode, where WebKitGTK writes under the XDG cache and data directories.
   *
   * @param perUser true in per-user mode
   * @return false where the guide opens outside the process
   */
  boolean embedsGuide(boolean perUser);

  /** The embedded browser's data folder. */
  interface BrowserData {
    /**
     * Returns the folder.
     *
     * @return the folder, which need not exist
     * @throws IOException when it cannot be resolved
     */
    Path folder() throws IOException;
  }
}
