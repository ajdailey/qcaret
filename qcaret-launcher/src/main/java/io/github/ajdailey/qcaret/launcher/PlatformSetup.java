package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.workspace.HomeFolder;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide settings applied before the Display exists. */
final class PlatformSetup {
  /** The SWT property naming the WebView2 user data folder. */
  static final String EDGE_DATA_DIR = "org.eclipse.swt.browser.EdgeDataDir";

  /** The settings directory's cache folder. */
  static final String CACHE = "cache";

  /** The WebView2 folder's name in the cache folder. */
  static final String WEBVIEW2 = "webview2";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformSetup.class);

  private PlatformSetup() {}

  /**
   * In explicit and portable mode on Linux, sets {@code user.home} to the settings directory so GTK
   * SWT writes its {@code .swt/trims.prefs} there instead of the user's home, keeping the first
   * value in {@link HomeFolder#PROPERTY} for the workspace browser's Home. On Windows, in every
   * mode, sets {@link #EDGE_DATA_DIR} to {@code <settings>/cache/webview2}, so an embedded WebView2
   * browser writes its data inside the settings directory.
   *
   * @param osName the {@code os.name} property
   * @param settings the settings directory
   */
  static void apply(String osName, SettingsLocation settings) {
    if (settings.mode() != SettingsMode.PER_USER && "Linux".equals(osName)) {
      if (System.getProperty(HomeFolder.PROPERTY) == null) {
        System.setProperty(HomeFolder.PROPERTY, System.getProperty("user.home", ""));
      }
      System.setProperty("user.home", settings.directory().toString());
    }
    if (osName.startsWith("Windows")) {
      try {
        System.setProperty(
            EDGE_DATA_DIR,
            SafePaths.child(SafePaths.child(settings.directory(), CACHE), WEBVIEW2).toString());
      } catch (IOException e) {
        LOG.warn("No WebView2 data folder: {}", LogText.oneLine(e.getMessage()));
      }
    }
  }
}
