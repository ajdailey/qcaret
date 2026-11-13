package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What one open document knows about its file, and the save and reload running on it. A tab move
 * shares this object between the old entry and the new one, so a field added here moves with the
 * tab. Late results of the old entry reach it: each compares its token and the entry's {@code
 * closed} flag before it writes here.
 *
 * <p>Two baselines exist. {@link #baseline} is what a save expects to find on disk. {@code
 * watch.baseline()} is the last state a check saw, and differs after Keep Mine: the watch takes the
 * file the user declined so it stops reporting, while {@link #baseline} stays, so a save still
 * finds the conflict.
 *
 * <p>Confined to the UI thread; {@link #writing} is the one field a worker touches.
 */
final class OpenFile {
  /** The real path, or null for an untitled document. */
  Path path;

  /** The path the user opened, which the watch follows; null before a watch exists. */
  Path given;

  /** What a save expects to find on disk; null for a file not yet on disk. */
  FileStamp baseline;

  /** The charset the user chose, or null when it is detected. */
  Charset forced;

  /** The check of {@link #given}, or null. */
  ExternalChanges.Watch watch;

  /** The file is gone and the buffer stays, dirty. */
  boolean missing;

  /** The text at the moment the file went missing, when it matched the file; else null. */
  DocumentSnapshot cleanAtMissing;

  /** The file's bytes do not encode back from the text: Save refuses while read-only. */
  boolean bytesGuarded;

  /** The guard alone made the document read-only, so a reload that ends the guard clears it. */
  boolean readOnlyByGuard;

  /** Held on a worker while a save writes this file. */
  final ReentrantLock writing = new ReentrantLock();

  /** Cancels the running save, or null. Cleared by a cancel while the writer still runs. */
  CancellationToken saving;

  /** Cancels the running Save a Copy As, or null. */
  CancellationToken copying;

  /** Cancels the running reload, or null. */
  CancellationToken reloading;

  /** Reloads retried since the document last held still. */
  int reloadAttempts;

  /** What the check that started a reload found; a refused reload takes it as the baseline. */
  FileBaseline reloadCheck;

  /** The document was clean when the running reload took its snapshot. */
  boolean reloadOfClean;

  /** A change or Reload from Disk prompt is open. */
  boolean changePrompt;

  /** A workspace browser rename of the file or a folder above it runs; Save and reload refuse. */
  boolean renaming;

  OpenFile(Path path, FileStamp baseline) {
    this.path = path;
    this.baseline = baseline;
  }

  /** Returns the file a save writes: the path the user opened, or the real path without one. */
  Path target() {
    return given == null ? path : given;
  }

  /** Records that the buffer now matches a file on disk, as a save and a reload do. */
  void current(Path real, FileStamp stamp) {
    path = real;
    baseline = stamp;
    missing = false;
    cleanAtMissing = null;
  }

  /**
   * Starts the round-trip guard.
   *
   * @param readOnly whether the document is read-only already
   */
  void guard(boolean readOnly) {
    readOnlyByGuard |= !readOnly;
    bytesGuarded = true;
  }

  /**
   * Ends the round-trip guard.
   *
   * @return true when the guard alone made the document read-only
   */
  boolean endGuard() {
    boolean clear = readOnlyByGuard;
    bytesGuarded = false;
    readOnlyByGuard = false;
    return clear;
  }

  /**
   * Marks the file gone.
   *
   * @param clean the text when it matched the file, else null
   */
  void markMissing(DocumentSnapshot clean) {
    missing = true;
    watch.setMissing(true);
    cleanAtMissing = clean;
  }

  /**
   * Ends the missing mark.
   *
   * @return the text recorded when the file went missing, or null
   */
  DocumentSnapshot clearMissing() {
    DocumentSnapshot clean = cleanAtMissing;
    missing = false;
    watch.setMissing(false);
    cleanAtMissing = null;
    return clean;
  }

  /** Work that holds this file until it ends. */
  enum Work {
    /** A save, Save As or save cleanup: {@link #saving}. */
    SAVE,
    /** A Save a Copy As: {@link #copying}. */
    COPY,
    /** A reload: {@link #reloading}. */
    RELOAD,
    /** A change or Reload from Disk prompt: {@link #changePrompt}. */
    CHANGE_PROMPT,
    /** A workspace browser rename: {@link #renaming}. */
    RENAME
  }

  /**
   * Returns the work in flight on this file. A tab move and a workspace Rename or Delete refuse
   * while any is.
   *
   * @return the work, empty when none runs
   */
  Set<Work> inFlight() {
    Set<Work> work = EnumSet.noneOf(Work.class);
    if (saving != null) {
      work.add(Work.SAVE);
    }
    if (copying != null) {
      work.add(Work.COPY);
    }
    if (reloading != null) {
      work.add(Work.RELOAD);
    }
    if (changePrompt) {
      work.add(Work.CHANGE_PROMPT);
    }
    if (renaming) {
      work.add(Work.RENAME);
    }
    return work;
  }
}
