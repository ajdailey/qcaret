package io.github.ajdailey.qcaret.app.controller;

import java.nio.file.Path;

/**
 * A rename in the workspace browser, applied to what follows the renamed entry: an open file's real
 * path, the path it was opened by and its watch, closed and recent paths. A renamed link keeps its
 * target, so only paths through the link move.
 *
 * @param from the entry before
 * @param to the entry after; its real path is null exactly when {@code from}'s is
 */
record PathMove(BrowserPath from, BrowserPath to) {
  /**
   * Returns where a real path lies after the rename.
   *
   * @param file a real path, or null
   * @return the moved path, or {@code file} when the rename does not reach it
   */
  Path real(Path file) {
    Path moved = relocate(file, from.real(), to.real());
    return moved != file ? moved : relocate(file, from.path(), to.path());
  }

  /**
   * Returns where a path the user opened lies after the rename.
   *
   * @param file the path, or null
   * @return the moved path, or {@code file} when the rename does not reach it
   */
  Path given(Path file) {
    Path moved = relocate(file, from.path(), to.path());
    return moved != file ? moved : relocate(file, from.real(), to.real());
  }

  /**
   * Moves an open file's paths and resumes its watch at the new path; the baseline stays, since a
   * rename keeps the file's bytes and stamp.
   *
   * @param file the open file, covered by {@code from}
   * @param changes the watch's owner
   * @return true when the file's name changed
   */
  boolean apply(OpenFile file, ExternalChanges changes) {
    Path before = file.path;
    file.path = real(file.path);
    Path given = given(file.given);
    if (file.watch != null && given != null) {
      changes.resume(file.watch, given, file.watch.baseline());
      file.watch.setMissing(file.missing);
    }
    file.given = given;
    return before != null
        && file.path != null
        && !String.valueOf(before.getFileName()).equals(String.valueOf(file.path.getFileName()));
  }

  private static Path relocate(Path file, Path old, Path now) {
    if (!BrowserPath.under(file, old) || now == null) {
      return file;
    }
    return now.resolve(old.relativize(file));
  }
}
