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
 */
public record StartupRequest(
    List<FileRequest> files,
    Integer width,
    Integer height,
    Integer x,
    Integer y,
    Path settingsDirectory,
    boolean multi) {
  /** Copies {@code files}. */
  public StartupRequest {
    files = List.copyOf(files);
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
