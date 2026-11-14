package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The file {@code qcaret_error.flag} in the settings directory, present after a run that quit for
 * UI-thread failures: empty until a launch shows the notice, then the line {@value #SHOWN}. A
 * launch that restores the session and finds it does not restore the saved session's windows.
 * Blocking; a failure is logged at WARN and not thrown. Uses no SWT and starts no thread, so the
 * failure handler can call {@link #write} on the UI thread.
 */
public final class ErrorExitMarker {
  /** The file name in the settings directory. */
  public static final String FILE = "qcaret_error.flag";

  /** The content once a launch showed the notice. */
  public static final String SHOWN = "noticeShown";

  /** What the file records. */
  public enum State {
    /** No file. */
    ABSENT,
    /** The file exists and no launch showed the notice. */
    PENDING,
    /** A launch showed the notice. */
    SHOWN
  }

  private static final Logger LOG = LoggerFactory.getLogger(ErrorExitMarker.class);

  private final SettingsLocation location;

  /**
   * Creates the marker.
   *
   * @param location the settings directory
   */
  public ErrorExitMarker(SettingsLocation location) {
    this.location = location;
  }

  /**
   * Writes the marker empty and atomically, creating a per-user directory first.
   *
   * @return false when the write failed
   */
  public boolean write() {
    return write(new byte[0]);
  }

  /**
   * Records atomically that a launch showed the notice.
   *
   * @return false when the write failed
   */
  public boolean markShown() {
    return write((SHOWN + "\n").getBytes(StandardCharsets.US_ASCII));
  }

  private boolean write(byte[] content) {
    try {
      Path dir = location.directory();
      if (location.mode() == SettingsMode.PER_USER && !Files.isDirectory(dir)) {
        SafePaths.settingsDirectory(dir);
      }
      AtomicFiles.write(SafePaths.child(dir, FILE), content);
      return true;
    } catch (IOException e) {
      LOG.warn(
          "Cannot write {} in {}: {}",
          FILE,
          LogText.oneLine(location.directory()),
          LogText.oneLine(e.getMessage()));
      return false;
    }
  }

  /**
   * Reads the marker; any content other than {@value #SHOWN} reads as {@link State#PENDING}.
   *
   * @return the state; {@link State#ABSENT} when the file is missing or cannot be read
   */
  public State read() {
    try (InputStream in = Files.newInputStream(SafePaths.child(location.directory(), FILE))) {
      String text = new String(in.readNBytes(SHOWN.length() + 2), StandardCharsets.US_ASCII);
      return SHOWN.equals(text.strip()) ? State.SHOWN : State.PENDING;
    } catch (NoSuchFileException e) {
      return State.ABSENT;
    } catch (IOException e) {
      LOG.warn(
          "Cannot read {} in {}: {}",
          FILE,
          LogText.oneLine(location.directory()),
          LogText.oneLine(e.getMessage()));
      return State.ABSENT;
    }
  }

  /** Deletes the marker. */
  public void clear() {
    try {
      Files.deleteIfExists(SafePaths.child(location.directory(), FILE));
    } catch (IOException e) {
      LOG.warn(
          "Cannot delete {} in {}: {}",
          FILE,
          LogText.oneLine(location.directory()),
          LogText.oneLine(e.getMessage()));
    }
  }
}
