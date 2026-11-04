package io.github.ajdailey.qcaret.app.about;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * The user guide shipped in the jar: one self-contained HTML page. A browser shows the copy {@link
 * #writeCopy} puts in the settings directory's {@code cache} folder; the page loads nothing from
 * elsewhere. The page in this build is a placeholder.
 */
public final class UserGuide {
  /** The page's resource name. */
  static final String RESOURCE = "/io/github/ajdailey/qcaret/app/guide/guide.html";

  /** The cache folder's name in the settings directory. */
  public static final String FOLDER = "cache";

  /** The copy's name in the cache folder. */
  public static final String FILE = "guide.html";

  private UserGuide() {}

  /**
   * Reports whether the build includes the page.
   *
   * @return false for a build that skipped the guide
   */
  public static boolean included() {
    return UserGuide.class.getResource(RESOURCE) != null;
  }

  /**
   * Copies the page to {@code <settings>/cache/guide.html}, creating the folder owner-only and
   * replacing the file atomically; streams it, so the page is never held in the heap. Blocks; call
   * on a worker.
   *
   * @param settings the settings directory
   * @return the copy
   * @throws IOException when the build has no guide, or the folder or the file cannot be written
   */
  @WorkerThread
  public static Path writeCopy(Path settings) throws IOException {
    try (InputStream page = UserGuide.class.getResourceAsStream(RESOURCE)) {
      if (page == null) {
        throw new IOException("The user guide is not included in this build");
      }
      Path folder = SafePaths.settingsDirectory(SafePaths.child(settings, FOLDER));
      Path copy = SafePaths.child(folder, FILE);
      AtomicFiles.write(copy, page);
      return copy;
    }
  }
}
