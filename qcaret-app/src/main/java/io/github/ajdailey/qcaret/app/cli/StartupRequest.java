package io.github.ajdailey.qcaret.app.cli;

import java.nio.file.Path;
import java.util.List;

/**
 * What the command line asks the application to open and how. Null width, height, x or y means not
 * given.
 *
 * @param files the positional files, in order
 * @param width {@code --window-width}: client-area width in pixels
 * @param height {@code --window-height}: client-area height in pixels
 * @param x {@code --window-x}: left edge of the window in display coordinates
 * @param y {@code --window-y}: top edge of the window in display coordinates
 * @param settingsDirectory {@code --settings}, resolved against the working directory and not yet
 *     validated; null when not given
 * @param multi {@code -m}: run as an independent instance
 * @param newWindow {@code -n}: open in a new window of the running instance
 * @param session {@code --session} or {@code --no-session}
 * @param diffLeft {@code --diff}: the left file, null when not given
 * @param diffRight {@code --diff}: the right file, null when not given
 */
public record StartupRequest(
    List<FileRequest> files,
    Integer width,
    Integer height,
    Integer x,
    Integer y,
    Path settingsDirectory,
    boolean multi,
    boolean newWindow,
    SessionMode session,
    Path diffLeft,
    Path diffRight) {
  /** Copies {@code files}. */
  public StartupRequest {
    files = List.copyOf(files);
  }

  /**
   * A request with only the files, the geometry and {@code -m}.
   *
   * @param files the positional files, in order
   * @param width {@code --window-width}
   * @param height {@code --window-height}
   * @param x {@code --window-x}
   * @param y {@code --window-y}
   * @param settingsDirectory {@code --settings}
   * @param multi {@code -m}
   */
  public StartupRequest(
      List<FileRequest> files,
      Integer width,
      Integer height,
      Integer x,
      Integer y,
      Path settingsDirectory,
      boolean multi) {
    this(
        files,
        width,
        height,
        x,
        y,
        settingsDirectory,
        multi,
        false,
        SessionMode.DEFAULT,
        null,
        null);
  }

  /**
   * What a handoff carries to the running instance.
   *
   * @param files the files with absolute paths
   * @param newWindow {@code -n}
   * @param x {@code --window-x}
   * @param y {@code --window-y}
   * @param width {@code --window-width}
   * @param height {@code --window-height}
   * @param diffLeft the left file of {@code --diff}, or null
   * @param diffRight the right file of {@code --diff}, or null
   * @return the request
   */
  public static StartupRequest handoff(
      List<FileRequest> files,
      boolean newWindow,
      Integer x,
      Integer y,
      Integer width,
      Integer height,
      Path diffLeft,
      Path diffRight) {
    return new StartupRequest(
        files,
        width,
        height,
        x,
        y,
        null,
        false,
        newWindow,
        SessionMode.DEFAULT,
        diffLeft,
        diffRight);
  }

  /**
   * Reports whether this start restores the previous session.
   *
   * @return true for {@code --session}, and by default when no file or comparison is named
   */
  public boolean restoresSession() {
    return session == SessionMode.RESTORE
        || (session == SessionMode.DEFAULT && files.isEmpty() && diffLeft == null);
  }

  /**
   * Reports whether any {@code --window-*} option was given.
   *
   * @return true when the command line places or sizes the window
   */
  public boolean placesWindow() {
    return width != null || height != null || x != null || y != null;
  }
}
