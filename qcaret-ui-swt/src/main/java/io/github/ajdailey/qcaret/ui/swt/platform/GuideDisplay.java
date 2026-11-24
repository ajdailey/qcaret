package io.github.ajdailey.qcaret.ui.swt.platform;

import java.nio.file.Path;

/**
 * Shows the user guide in the application, one implementation per platform from {@link
 * PlatformTraits#guideDisplay}: {@link NoticeGuideDisplay} on GTK 4, where WebKitGTK 6.0 aborts the
 * JVM when a {@code Browser} starts ("Failed to fully launch dbus-proxy"), so a notice names the
 * file and no {@code Browser} is created; {@link BrowserGuideDisplay} elsewhere. Call on the UI
 * thread.
 */
public interface GuideDisplay {
  /** The running platform's guide display. */
  GuideDisplay PLATFORM = PlatformTraits.PLATFORM.guideDisplay();

  /**
   * Shows the guide.
   *
   * @param file the guide's file
   * @param shows the window's ways to show it
   * @return false when a browser window could not be created
   */
  boolean show(Path file, Shows shows);

  /** A window's ways to show the guide. */
  interface Shows {
    /**
     * Shows the guide in a browser window.
     *
     * @param file the guide's file
     * @return false when the browser window could not be created
     */
    boolean browser(Path file);

    /**
     * Shows a notice naming the guide's file.
     *
     * @param file the guide's file
     */
    void notice(Path file);
  }
}
