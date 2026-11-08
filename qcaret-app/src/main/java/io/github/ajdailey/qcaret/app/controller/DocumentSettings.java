package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.text.FileSettings;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * One window's per-file settings: what each document's file declares, applied over the defaults and
 * the remembered state, and the {@link SaveCleanup} a save runs with them. {@link
 * DocumentController} owns the documents and the saves, supplies each document as a {@link Tab} and
 * continues a write once {@link #prepareSave} hands it back. Confined to the UI thread.
 */
final class DocumentSettings {
  /** Cleanups dropped because the document changed meanwhile before a write goes on without one. */
  static final int CLEANUP_ATTEMPTS = 3;

  /** One open document as this class sees it. */
  interface Tab {
    /** Returns the document. */
    Document document();

    /** Returns the document's file link. */
    OpenFile file();

    /** Reports whether the tab is closed. */
    boolean closed();

    /** Returns the selections a cleanup's edit maps. */
    SelectionModel selections();

    /** Returns the document's declared settings and save state. */
    State declared();

    /**
     * Sets the document's tab width and indentation, telling its editors of a new width.
     *
     * @param tabWidth columns per tab stop
     * @param insertSpaces true to indent with spaces
     */
    void setTabSettings(int tabWidth, boolean insertSpaces);

    /** Shows the new settings: the tab's title and tool tip and the document state. */
    void settingsChanged();
  }

  /** A document's declared settings and the progress of its save's cleanup. */
  static final class State {
    /** What the file's EditorConfig properties and modelines declare. */
    FileSettings settings = FileSettings.NONE;

    /** A Save As target's settings, applied once the write succeeds; or null. */
    FileSettings pending;

    /** The running write ran its cleanup. */
    boolean prepared;

    /** Cleanups dropped because the document changed meanwhile, in this save. */
    int attempts;
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final ActivityIndicator activity;
  private FileSettingsReader reader;

  DocumentSettings(Workers workers, UiDispatcher ui, ActivityIndicator activity) {
    this.workers = workers;
    this.ui = ui;
    this.activity = activity;
  }

  /**
   * Reads from these sources from now on; the first call creates the reader.
   *
   * @param editorConfig true to read {@code .editorconfig} files
   * @param modelines true to read modelines
   * @return the reader when this call created it, else null
   */
  FileSettingsReader setSources(boolean editorConfig, boolean modelines) {
    FileSettingsReader created = null;
    if (reader == null) {
      reader = new FileSettingsReader();
      created = reader;
    }
    reader.setSources(editorConfig, modelines);
    return created;
  }

  /** Returns the reader, or null while no source was set. */
  FileSettingsReader reader() {
    return reader;
  }

  /**
   * Returns a tab's tool tip with the line naming where its settings came from.
   *
   * @param tip the tool tip without it
   * @param state the document's state
   * @return the tool tip
   */
  static String toolTip(String tip, State state) {
    String from = FileSettingsReader.sources(state.settings);
    return from == null ? tip : tip + "\n" + from;
  }

  /**
   * Applies a file's declared settings over the defaults and the remembered state: the width and
   * indentation, and the ending Enter inserts while the text has no break.
   *
   * @param d the document
   * @param s the settings
   */
  void apply(Tab d, FileSettings s) {
    d.declared().settings = s;
    Document document = d.document();
    if (s.tabWidth() > 0 || s.insertSpaces() != null) {
      d.setTabSettings(
          s.tabWidth() > 0 ? s.tabWidth() : document.tabWidth(),
          s.insertSpaces() != null ? s.insertSpaces() : document.insertSpaces());
    }
    if (s.lineEnding() != null && noBreaks(document)) {
      document.setLineEnding(s.lineEnding());
    }
    d.settingsChanged();
  }

  /**
   * Reads the settings of a file that does not exist yet on a worker and applies them, the declared
   * charset included while the document is empty and clean.
   *
   * @param d the document
   * @param path the file
   */
  void readForNewFile(final Tab d, Path path) {
    if (reader == null) {
      return;
    }
    FileSettingsReader.readLater(
        workers,
        ui,
        reader,
        path,
        d.document().text(),
        new Consumer<FileSettings>() {
          @Override
          public void accept(FileSettings found) {
            newFile(d, found);
          }
        });
  }

  private void newFile(Tab d, FileSettings found) {
    if (d.closed() || !found.any()) {
      return;
    }
    Document document = d.document();
    TextEncoding encoding = found.encoding();
    if (encoding != null
        && document.text().length() == 0
        && !document.isDirty()
        && !encoding.equals(document.encoding())) {
      document.setEncoding(encoding);
      document.markSaved(document.snapshot());
    }
    apply(d, found);
  }

  /**
   * Reads a moved document's settings on a worker and applies them, as a Save As to that path does;
   * a result for a path the document has left since is dropped.
   *
   * @param d the document
   */
  void readForMove(final Tab d) {
    final Path path = d.file().path;
    if (reader == null || path == null) {
      return;
    }
    FileSettingsReader.readLater(
        workers,
        ui,
        reader,
        path,
        d.document().text(),
        new Consumer<FileSettings>() {
          @Override
          public void accept(FileSettings found) {
            if (!d.closed() && path.equals(d.file().path)) {
              apply(d, found);
            }
          }
        });
  }

  /**
   * Starts a write's {@link SaveCleanup} on a worker when the settings it saves with ask for one;
   * the edit applies as one undo step before the write's snapshot. An edit made meanwhile drops the
   * cleanup and computes it again, at most {@value #CLEANUP_ATTEMPTS} times; then the write goes on
   * without it. Esc or a close during the cleanup ends the save unwritten.
   *
   * @param d the document
   * @param write runs the write again once the cleanup is applied
   * @param stop ends the save unwritten
   * @return true when the write continues from the worker's result
   */
  boolean prepareSave(final Tab d, final Runnable write, final Runnable stop) {
    final State state = d.declared();
    final Document document = d.document();
    FileSettings saving = state.pending != null ? state.pending : state.settings;
    boolean editable = !document.isReadOnly() && state.attempts < CLEANUP_ATTEMPTS;
    if (state.prepared || !editable || !SaveCleanup.wanted(saving)) {
      state.prepared = false;
      state.attempts = 0;
      return false;
    }
    document.clearPreedit();
    final DocumentSnapshot snapshot = document.snapshot();
    LineEnding ending =
        noBreaks(document) && saving.lineEnding() != null
            ? saving.lineEnding()
            : document.lineEnding();
    final CancellationToken token = new CancellationToken();
    d.file().saving = token;
    boolean started =
        SaveCleanup.start(
            workers,
            ui,
            activity,
            snapshot.text(),
            saving,
            ending,
            token,
            new Consumer<EditBatch>() {
              @Override
              public void accept(EditBatch cleanup) {
                if (prepared(d, token, snapshot.revision(), cleanup)) {
                  write.run();
                } else {
                  stop.run();
                }
              }
            });
    if (!started) {
      d.file().saving = null;
      state.pending = null;
      stop.run();
    }
    return true;
  }

  /**
   * Applies a save's cleanup and marks the write ready; false ends the save. A cleanup computed
   * before an edit leaves the write unready, so it is computed again.
   */
  private static boolean prepared(
      Tab d, CancellationToken token, long revision, EditBatch cleanup) {
    State state = d.declared();
    OpenFile file = d.file();
    if (file.saving != token || d.closed()) {
      if (file.saving == token) {
        file.saving = null;
      }
      state.attempts = 0;
      state.pending = null;
      return false;
    }
    file.saving = null;
    Document document = d.document();
    if (cleanup != null && document.revision() != revision) {
      state.attempts++;
      return true;
    }
    if (cleanup != null && !document.isReadOnly()) {
      document.clearPreedit();
      document.apply(cleanup, d.selections(), null);
    }
    state.prepared = true;
    return true;
  }

  private static boolean noBreaks(Document document) {
    LineEndingStats breaks = document.lineEndings();
    return breaks.lf() + breaks.crLf() + breaks.cr() == 0;
  }
}
