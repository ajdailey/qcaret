package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * One window's open documents as the workspace browser's Rename and Delete reach them: which lie
 * under an entry, the hold a rename puts on them, the move it applies and the check after a delete.
 * {@link DocumentController} owns the documents and supplies them as {@link Tab}s. Confined to the
 * UI thread.
 *
 * <p>While a rename runs, the held documents' checks stop, so a check that finds the source path
 * empty reports nothing; Save, Save As onto a path under the entry and reload refuse with a notice,
 * so nothing writes or reads the source path until {@link #moved} or {@link #release} ends the
 * hold.
 */
final class DocumentsUnder {
  /** One open document as this class sees it. */
  interface Tab {
    /** Returns the document's file link. */
    OpenFile file();

    /** Reports whether the document has unsaved changes. */
    boolean dirty();

    /** Returns the tab's name. */
    String name();

    /**
     * Reports that a rename moved the document's file: the titles change, and a new name detects
     * the grammar again.
     *
     * @param renamed true when the file name changed
     */
    void moved(boolean renamed);
  }

  private final ExternalChanges changes;
  private final Deque<Path> closedPaths;
  private final MainView view;
  private final Supplier<List<Tab>> tabs;
  private final Runnable sessionChanged;
  private final List<BrowserPath> held = new ArrayList<BrowserPath>();

  DocumentsUnder(
      ExternalChanges changes,
      Deque<Path> closedPaths,
      MainView view,
      Supplier<List<Tab>> tabs,
      Runnable sessionChanged) {
    this.changes = changes;
    this.closedPaths = closedPaths;
    this.view = view;
    this.tabs = tabs;
    this.sessionChanged = sessionChanged;
  }

  /** Returns the names of the documents under an entry that have unsaved changes. */
  List<String> dirtyUnder(BrowserPath entry) {
    List<String> names = new ArrayList<String>();
    for (Tab tab : tabs.get()) {
      if (entry.covers(tab.file()) && tab.dirty()) {
        names.add(tab.name());
      }
    }
    return names;
  }

  /** Reports whether a save, a reload or a change prompt holds a document under an entry. */
  boolean busyUnder(BrowserPath entry) {
    for (Tab tab : tabs.get()) {
      if (entry.covers(tab.file()) && tab.file().running()) {
        return true;
      }
    }
    return false;
  }

  /** Holds the documents under an entry while a rename runs. */
  void hold(BrowserPath entry) {
    held.add(entry);
    for (Tab tab : tabs.get()) {
      OpenFile file = tab.file();
      if (entry.covers(file)) {
        file.renaming = true;
        if (file.watch != null) {
          changes.pause(file.watch);
        }
      }
    }
  }

  /** Ends a hold: the documents still under the entry, which a rename did not move, resume. */
  void release(BrowserPath entry) {
    held.remove(entry);
    for (Tab tab : tabs.get()) {
      OpenFile file = tab.file();
      if (entry.covers(file)) {
        file.renaming = false;
        if (file.watch != null) {
          changes.resume(file.watch, file.watch.path(), file.watch.baseline());
          file.watch.setMissing(file.missing);
        }
      }
    }
  }

  /** Moves the documents and the closed paths under a renamed entry; edits stay. */
  void moved(PathMove move) {
    boolean any = false;
    for (Tab tab : tabs.get()) {
      OpenFile file = tab.file();
      if (move.from().covers(file)) {
        file.renaming = false;
        tab.moved(move.apply(file, changes));
        any = true;
      }
    }
    List<Path> closed = new ArrayList<Path>(closedPaths);
    closedPaths.clear();
    for (Path path : closed) {
      closedPaths.addLast(move.real(path));
    }
    if (any) {
      view.documentStateChanged();
      sessionChanged.run();
    }
  }

  /** Checks the files of the documents under a deleted entry; a gone one is marked missing. */
  void deleted(BrowserPath entry) {
    for (Tab tab : tabs.get()) {
      OpenFile file = tab.file();
      if (entry.covers(file) && file.watch != null) {
        changes.check(file.watch);
      }
    }
  }

  /**
   * Shows the notice and returns true when a rename holds a document or a save target.
   *
   * @param file the document's file link
   * @param name the document's name
   * @param target the file the save writes
   * @return true when the save must not start
   */
  boolean refusesSave(OpenFile file, String name, Path target) {
    boolean refused = file.renaming;
    for (BrowserPath entry : held) {
      refused |= BrowserPath.under(target, entry.path()) || BrowserPath.under(target, entry.real());
    }
    if (refused) {
      view.showInfo(UiText.SAVE_TITLE.text(), UiText.RENAME_RUNNING.format(name));
    }
    return refused;
  }

  /**
   * Shows the notice and returns true when a rename holds a document.
   *
   * @param file the document's file link
   * @param name the document's name
   * @return true when the reload must not start
   */
  boolean refusesReload(OpenFile file, String name) {
    if (file.renaming) {
      view.showNotice(UiText.RENAME_RUNNING.format(name));
    }
    return file.renaming;
  }
}
