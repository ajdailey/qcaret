package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.os.FileConfinement;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide settings applied before the Display exists. */
final class PlatformSetup {
  /** The settings directory's cache folder. */
  static final String CACHE = "cache";

  /** The WebView2 folder's name in the cache folder. */
  static final String WEBVIEW2 = "webview2";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformSetup.class);

  private PlatformSetup() {}

  /**
   * Applies the system's {@link FileConfinement}: in explicit and portable mode on Linux, moves
   * {@code user.home} to the settings directory; on Windows, in every mode, places WebView2's data
   * in {@code <settings>/cache/webview2}.
   *
   * @param os the system
   * @param settings the settings directory
   */
  static void apply(OsTraits os, final SettingsLocation settings) {
    FileConfinement confinement = os.fileConfinement();
    confinement.confineHome(settings.perUser(), settings.directory());
    try {
      confinement.placeBrowserData(
          new FileConfinement.BrowserData() {
            @Override
            public Path folder() throws IOException {
              return SafePaths.child(SafePaths.child(settings.directory(), CACHE), WEBVIEW2);
            }
          });
    } catch (IOException e) {
      LOG.warn("No WebView2 data folder: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
