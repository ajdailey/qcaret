package io.github.ajdailey.qcaret.app.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The files that Save, Save As, Save a Copy As and a checksum list's Save As write, in every
 * window, from the start of the write until its result reaches the UI thread. A workspace Rename or
 * Delete refuses an entry with one under it: the write would fail and could leave a {@code
 * .qcaret-tmp} file in the renamed folder. Targets compare as the user named them. Confined to the
 * UI thread.
 */
final class RunningWrites {
  private final List<Path> targets = new ArrayList<Path>();

  /**
   * A write started.
   *
   * @param target the file it writes
   */
  void started(Path target) {
    targets.add(target.toAbsolutePath().normalize());
  }

  /**
   * A write ended.
   *
   * @param target the file passed to {@link #started}
   */
  void ended(Path target) {
    targets.remove(target.toAbsolutePath().normalize());
  }

  /**
   * Reports whether a running write's target lies at or under an entry.
   *
   * @param entry the workspace entry
   * @return true when one does
   */
  boolean under(BrowserPath entry) {
    for (Path target : targets) {
      if (BrowserPath.under(target, entry.path()) || BrowserPath.under(target, entry.real())) {
        return true;
      }
    }
    return false;
  }
}
