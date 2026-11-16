package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the running platform's traits from the toolkit SWT loaded. SWT loads GTK 3 when GTK 4
 * fails to load and GTK 4 when GTK 3 fails, so {@code SWT_GTK4} does not select the GTK version.
 */
final class LoadedToolkit {
  private static final Logger LOG = LoggerFactory.getLogger(LoadedToolkit.class);

  private LoadedToolkit() {}

  /**
   * Returns the running platform's traits. On GTK it initializes {@code Display}, whose static
   * initializer loads SWT's GTK library and records the GTK version, and logs a warning when {@code
   * SWT_GTK4=1} is set and GTK 3 loaded.
   *
   * @return the traits
   */
  static PlatformTraits traits() {
    String platform = SWT.getPlatform();
    if (!"gtk".equals(platform)) {
      return choose(platform, null);
    }
    try {
      Display.getCurrent();
    } catch (LinkageError | SWTError e) {
      LOG.debug("SWT's GTK library failed to load", e);
    }
    String version = System.getProperty(GtkTraits.GTK_VERSION);
    if (fellBack("1".equals(System.getenv("SWT_GTK4")), version)) {
      LOG.warn("SWT_GTK4=1 is set but GTK 4 failed to load; running on GTK {}", version);
    }
    return choose(platform, version);
  }

  /**
   * Returns a platform's traits for the GTK version SWT recorded.
   *
   * @param platform an {@link SWT#getPlatform} value
   * @param version the {@code org.eclipse.swt.internal.gtk.version} value, or null
   * @return GTK 4 traits for a {@code gtk} platform at major version 4, GTK 3 traits for any other
   *     {@code gtk} version
   */
  static PlatformTraits choose(String platform, String version) {
    return PlatformTraits.of(platform, "gtk".equals(platform) && gtk4(version));
  }

  /**
   * Reports whether SWT loaded GTK 3 although GTK 4 was requested.
   *
   * @param requested whether {@code SWT_GTK4=1} is set
   * @param version the recorded GTK version, or null when no GTK library loaded
   * @return true when GTK 4 was requested and a GTK 3 version is recorded
   */
  static boolean fellBack(boolean requested, String version) {
    return requested && version != null && !gtk4(version);
  }

  /**
   * Reports whether a recorded GTK version is GTK 4.
   *
   * @param version the recorded GTK version, or null
   * @return true for a major version of 4
   */
  static boolean gtk4(String version) {
    return version != null && version.startsWith("4.");
  }
}
