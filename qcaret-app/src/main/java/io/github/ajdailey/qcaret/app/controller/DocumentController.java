package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BinaryFileException;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.io.ExternalModificationException;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The documents of one window, one tab each: New, Open, Save, Save As and Close, the
 * unsaved-changes prompt, split views and the window-wide word wrap setting. Confined to the UI
 * thread.
 *
 * <ul>
 *   <li>Open goes through {@link DocumentOpener}: the heap-budget check, the load and the rows on a
 *       worker. A missing file opens an empty document for its path; a file already open selects
 *       its tab. A document whose text does not encode back to the file's bytes opens read-only
 *       with a notice. A binary file opens only after the user accepts a read-only ISO-8859-1 view;
 *       that document is never written: Save and Save As refuse it, whatever calls them.
 *   <li>Reopen with an encoding loads the file again with that charset and replaces the clean
 *       document in its tab once loaded; a failed load leaves the document open. Save with an
 *       encoding changes only what the next save writes. Converting the line endings computes the
 *       new text on a {@code qcaret-cpu-} worker and applies it as one undo step, dropped when the
 *       document changed meanwhile.
 *   <li>Save writes a {@link DocumentSnapshot} through {@link DocumentFiles#save} on a {@code
 *       qcaret-io-} worker while editing goes on; the document is clean once the snapshot's state
 *       is current. A save while one runs for the same document shows a notice instead. Esc in the
 *       editor and closing the tab cancel a running save, which leaves the target unchanged when
 *       the cancel lands before the file is replaced.
 *   <li>Closing a dirty document asks Save, Don't Save or Cancel; Save closes once the save
 *       succeeds. {@link #closeAll} asks for each dirty document in tab order and stops at Cancel.
 *   <li>A closed document releases its {@link MemoryBudget} reservation. The paths of the last
 *       {@value #REOPEN_LIMIT} closed files are kept for Reopen Closed Tab, newest first.
 *   <li>Tab width, indentation with spaces and read-only are per document; a binary file's view
 *       stays read-only.
 *   <li>A tab shows its document in one view or two ({@link #split}); each view is an {@link
 *       EditorController} with its own selections and rows, and the focused one receives menu
 *       commands. Closing the tab disposes every view.
 * </ul>
 *
 * <p>Loads, saves and conversions each count as one operation of the view's {@link
 * io.github.ajdailey.qcaret.app.view.ActivityIndicator} until their worker ends. A change of the
 * selected document or of its file, dirty, read-only, encoding or line-ending state calls {@link
 * MainView#documentStateChanged}.
 */
public final class DocumentController {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);

  /** Closed files Reopen Closed Tab remembers. */
  static final int REOPEN_LIMIT = 20;

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final LoadOptions options;
  private final DocumentOpener opener;
  private final List<OpenDocument> documents = new ArrayList<OpenDocument>();
  private final Map<Path, Charset> reopening = new HashMap<Path, Charset>();
  private final Deque<Path> closedPaths = new ArrayDeque<Path>();
  private boolean disposing;
  private OpenDocument active;
  private int nextUntitled = 1;
  private boolean wrap;

  /** One open document and its tab. */
  private final class OpenDocument implements DocumentListener {
    final Document document;
    final String untitled;
    TabInput input;
    Path path;
    FileStamp baseline;
    long reserved;
    EditorController editor;
    final List<EditorController> editors = new ArrayList<EditorController>(2);
    boolean sideBySide;
    TabView tab;
    CancellationToken saving;
    CancellationToken converting;
    boolean binary;
    boolean closed;

    OpenDocument(Document document, Path path, FileStamp baseline, long reserved) {
      this.document = document;
      this.path = path;
      this.baseline = baseline;
      this.reserved = reserved;
      this.untitled = path == null ? UiText.TAB_UNTITLED.format(nextUntitled++) : null;
    }

    String name() {
      Path file = path == null ? null : path.getFileName();
      return file == null ? untitled : file.toString();
    }

    void updateTitle() {
      if (!closed) {
        String name = name();
        String title = (document.isDirty() ? "*" : "") + name;
        if (binary) {
          title = UiText.TAB_BINARY.format(title);
        } else if (document.isReadOnly()) {
          title = UiText.TAB_READ_ONLY.format(title);
        }
        tab.setTitle(title, path == null ? name : path.toString());
      }
    }

    @Override
    public void textChanged(DocumentChange change) {
      // The title follows the dirty state only.
    }

    @Override
    public void dirtyChanged(boolean dirty) {
      updateTitle();
      view.documentStateChanged();
    }
  }

  /**
   * Creates the controller.
   *
   * @param workers runs loads, saves and rewraps
   * @param ui receives worker results
   * @param view the window
   * @param budget the documents' heap share
   * @param options how files load
   */
  public DocumentController(
      Workers workers, UiDispatcher ui, MainView view, MemoryBudget budget, LoadOptions options) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
    this.options = options;
    this.opener =
        new DocumentOpener(
            workers,
            ui,
            budget,
            view,
            new DocumentOpener.Listener() {
              @Override
              public void opened(
                  Path path, LoadedFile file, DisplayProjection.Layout layout, long reserved) {
                loaded(path, file, layout, reserved);
              }

              @Override
              public void tooLarge(Path path, LoadCost cost, long available, long maxHeap) {
                refuse(path, cost, available, maxHeap);
              }

              @Override
              public void failed(Path path, IOException error) {
                loadFailed(path, error);
              }
            });
  }

  /**
   * Returns the number of open documents.
   *
   * @return the tab count
   */
  public int count() {
    return documents.size();
  }

  /**
   * Returns the editor of the selected tab.
   *
   * @return the editor, or null without a document
   */
  public EditorController activeEditor() {
    return active == null ? null : active.editor;
  }

  /**
   * Returns the path of the selected tab's document.
   *
   * @return the path, or null for an untitled document or no document
   */
  public Path activePath() {
    return active == null ? null : active.path;
  }

  /** Returns the selected document's state, or null without a document. */
  Document activeDocument() {
    return active == null ? null : active.document;
  }

  /** Reports whether the selected document is a binary file's read-only view. */
  boolean activeBinary() {
    return active != null && active.binary;
  }

  /** Returns the tab labels in tab order: each path, or the title of an untitled document. */
  List<String> labels() {
    List<String> labels = new ArrayList<String>();
    for (OpenDocument d : documents) {
      labels.add(d.path == null ? d.untitled : d.path.toString());
    }
    return labels;
  }

  /** Returns the selected document's tab index, or -1 without a document. */
  int activeIndex() {
    return documents.indexOf(active);
  }

  /** Returns the index of the document behind a tab's input, or -1 when it is closed. */
  int indexOf(TabInput input) {
    for (int i = 0; i < documents.size(); i++) {
      if (documents.get(i).input == input) {
        return i;
      }
    }
    return -1;
  }

  /** Reports whether the document at a tab index has a file. */
  boolean hasFile(int index) {
    return index >= 0 && index < documents.size() && documents.get(index).path != null;
  }

  /**
   * Reports whether word wrap is on for the window's editors.
   *
   * @return true when on
   */
  public boolean wrap() {
    return wrap;
  }

  /** Turns word wrap on or off in every editor of the window. */
  public void toggleWrap() {
    wrap = !wrap;
    for (OpenDocument d : documents) {
      for (EditorController e : d.editors) {
        e.setWrap(wrap);
      }
    }
  }

  // New and Open.

  /** Opens an empty untitled document, UTF-8 with the default line ending. */
  public void newDocument() {
    Document document =
        Document.of(
            "", TextEncoding.of(StandardCharsets.UTF_8, false), options.empty(), options.limits());
    add(
        document,
        null,
        null,
        0,
        DisplayProjection.Layout.empty(document.text()),
        documents.size(),
        false);
  }

  /** Asks for files and opens them. */
  public void open() {
    Path directory = active == null || active.path == null ? null : active.path.getParent();
    view.chooseFilesToOpen(
        directory,
        new Consumer<List<Path>>() {
          @Override
          public void accept(List<Path> paths) {
            open(paths);
          }
        });
  }

  /**
   * Opens files, each in its own tab once loaded.
   *
   * @param paths the files as the user named them
   */
  public void open(List<Path> paths) {
    for (Path path : paths) {
      opener.open(path, options);
    }
  }

  /**
   * Counts the loads still running.
   *
   * @return loads whose worker has not ended
   */
  public int loading() {
    return opener.running();
  }

  private void loaded(Path path, LoadedFile file, DisplayProjection.Layout layout, long reserved) {
    boolean reopen = reopening.remove(path) != null;
    int index = documents.size();
    for (OpenDocument d : documents) {
      if (path.equals(d.path)) {
        if (!reopen || d.document.isDirty()) {
          budget.release(reserved);
          d.tab.select();
          return;
        }
        index = documents.indexOf(d);
        discard(d);
        break;
      }
    }
    Document document = file.document();
    OpenDocument d = add(document, path, file.stamp(), reserved, layout, index, file.binary());
    if (file.binary()) {
      document.setReadOnly(true);
      d.updateTitle();
      view.documentStateChanged();
    } else if (!file.roundTrips()) {
      document.setReadOnly(true);
      view.showInfo(
          UiText.OPEN_TITLE.text(),
          UiText.OPEN_READ_ONLY.format(
              d.name(), file.detection().encoding().charset().displayName()));
      d.updateTitle();
      view.documentStateChanged();
    }
  }

  private void refuse(Path path, LoadCost cost, long available, long maxHeap) {
    long needed = cost.reserveBytes();
    long suggested = Math.max(1, (maxHeap + (needed - available)) / (1L << 30) + 1);
    view.showInfo(
        UiText.OPEN_TITLE.text(),
        UiText.OPEN_TOO_LARGE.format(
            String.valueOf(path.getFileName()),
            mebibytes(cost.fileBytes()),
            mebibytes(needed),
            mebibytes(available),
            mebibytes(maxHeap),
            suggested));
  }

  private void loadFailed(final Path path, IOException error) {
    reopening.remove(path);
    if (error instanceof BinaryFileException) {
      view.askOpenBinary(
          String.valueOf(path.getFileName()),
          new Runnable() {
            @Override
            public void run() {
              opener.open(path, options.asBinary());
            }
          });
      return;
    }
    if (error instanceof NoSuchFileException && path.isAbsolute() && path.getParent() != null) {
      Document document =
          Document.of(
              "",
              TextEncoding.of(StandardCharsets.UTF_8, false),
              options.empty(),
              options.limits());
      add(
          document,
          path,
          null,
          0,
          DisplayProjection.Layout.empty(document.text()),
          documents.size(),
          false);
      return;
    }
    String reason = LogText.oneLine(error.getMessage());
    LOG.debug("Open failed: {}", LogText.oneLine(String.valueOf(path)));
    view.showInfo(
        UiText.OPEN_TITLE.text(),
        UiText.OPEN_FAILED.format(String.valueOf(path.getFileName()), reason));
  }

  private OpenDocument add(
      Document document,
      Path path,
      FileStamp baseline,
      long reserved,
      DisplayProjection.Layout layout,
      int index,
      boolean binary) {
    final OpenDocument d = new OpenDocument(document, path, baseline, reserved);
    d.binary = binary;
    d.input = tabInput(d);
    SelectionModel selections = document.newSelectionModel();
    DisplayProjection projection = new DisplayProjection(document, layout);
    d.editor = newEditor(d, projection, selections);
    d.tab = view.addTab(projection, selections, d.editor, d.input, index);
    d.editor.attach(d.tab.firstView());
    document.addListener(d);
    documents.add(index, d);
    active = d;
    d.updateTitle();
    if (wrap) {
      d.editor.setWrap(true);
    }
    view.documentStateChanged();
    viewsChanged();
    return d;
  }

  private EditorController newEditor(
      final OpenDocument d, DisplayProjection projection, SelectionModel selections) {
    final EditorController editor =
        new EditorController(
            projection,
            selections,
            view,
            workers,
            ui,
            new Runnable() {
              @Override
              public void run() {
                cancelSave(d);
              }
            });
    editor.setViewportListener(
        new Runnable() {
          @Override
          public void run() {
            if (viewListener != null && active == d) {
              viewListener.viewportChanged(editor);
            }
          }
        });
    d.editors.add(editor);
    return editor;
  }

  private TabInput tabInput(final OpenDocument d) {
    return new TabInput() {
      @Override
      public void closeRequested() {
        close(d, null);
      }

      @Override
      public void activated() {
        if (!d.closed && active != d) {
          active = d;
          view.documentStateChanged();
          viewsChanged();
        }
      }

      @Override
      public void moved(int index) {
        documents.remove(d);
        documents.add(Math.max(0, Math.min(index, documents.size())), d);
      }

      @Override
      public void closeOthersRequested() {
        closeOthers(d);
      }

      @Override
      public void copyPathRequested(boolean nameOnly) {
        if (d.path != null) {
          Path name = d.path.getFileName();
          view.setText(nameOnly && name != null ? name.toString() : d.path.toString());
        }
      }

      @Override
      public void viewFocused(EditorInput editor) {
        for (EditorController e : d.editors) {
          if (e == editor && d.editor != e) {
            d.editor = e;
            view.documentStateChanged();
            viewsChanged();
          }
        }
      }
    };
  }

  // Save.

  /** Saves the selected document; an untitled one asks for a file. */
  public void save() {
    if (active != null) {
      save(active, null);
    }
  }

  /** Saves the selected document to a file the user picks. */
  public void saveAs() {
    if (active != null) {
      saveAs(active, null);
    }
  }

  /** Cancels a running save of the selected document. */
  public void cancelSave() {
    if (active != null) {
      cancelSave(active);
    }
  }

  private void save(OpenDocument d, Runnable then) {
    if (binaryNotice(d)) {
      return;
    }
    if (d.path == null) {
      saveAs(d, then);
    } else {
      write(d, d.path, d.baseline, then);
    }
  }

  private void saveAs(final OpenDocument d, final Runnable then) {
    if (binaryNotice(d) || savingNotice(d)) {
      return;
    }
    Path directory = d.path == null ? null : d.path.getParent();
    view.chooseSaveTarget(
        directory,
        d.name(),
        new Consumer<Path>() {
          @Override
          public void accept(Path target) {
            if (!d.closed) {
              write(d, target, target.equals(d.path) ? d.baseline : null, then);
            }
          }
        });
  }

  /** Shows the notice and returns true for a binary file's view, which is never written. */
  private boolean binaryNotice(OpenDocument d) {
    if (!d.binary) {
      return false;
    }
    view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_BINARY.format(d.name()));
    return true;
  }

  /** Shows the notice and returns true when a save of the document is running. */
  private boolean savingNotice(OpenDocument d) {
    if (d.saving == null) {
      return false;
    }
    view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_RUNNING.format(d.name()));
    return true;
  }

  private void cancelSave(OpenDocument d) {
    if (d.saving != null) {
      d.saving.cancel();
      d.saving = null;
    }
  }

  /** Writes a snapshot on a worker; on success records the stamp and runs {@code then}. */
  private void write(
      final OpenDocument d, final Path target, final FileStamp baseline, final Runnable then) {
    if (d.binary || savingNotice(d)) {
      return;
    }
    d.document.clearPreedit();
    final DocumentSnapshot snapshot = d.document.snapshot();
    final CancellationToken token = new CancellationToken();
    d.saving = token;
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                FileStamp stamp = null;
                IOException error = null;
                try {
                  stamp = DocumentFiles.save(target, snapshot, baseline, token);
                } catch (IOException e) {
                  error = e;
                } catch (CancellationException e) {
                  // Canceled: the target is unchanged unless the move had happened.
                } finally {
                  view.setIdle();
                  final FileStamp saved = stamp;
                  final IOException failure = error;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          saved(d, token, target, snapshot, saved, failure, then);
                        }
                      });
                }
              }
            });
    if (!started) {
      d.saving = null;
      view.setIdle();
    }
  }

  private void saved(
      OpenDocument d,
      CancellationToken token,
      Path target,
      DocumentSnapshot snapshot,
      FileStamp stamp,
      IOException error,
      Runnable then) {
    if (d.saving == token) {
      d.saving = null;
    }
    if (d.closed) {
      return;
    }
    if (stamp != null) {
      d.path = target;
      d.baseline = stamp;
      d.document.markSaved(snapshot);
      d.updateTitle();
      view.documentStateChanged();
      if (then != null) {
        then.run();
      }
    } else if (error instanceof ExternalModificationException) {
      view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_CHANGED_ON_DISK.format(d.name()));
    } else if (error != null) {
      view.showInfo(
          UiText.SAVE_TITLE.text(),
          UiText.SAVE_FAILED.format(d.name(), LogText.oneLine(error.getMessage())));
    }
  }

  // Close.

  /** Closes the selected document, asking first when it has unsaved changes. */
  public void closeActive() {
    if (active != null) {
      close(active, null);
    }
  }

  /**
   * Closes every document in tab order, asking for each dirty one, then runs {@code then}. A Cancel
   * stops there and {@code then} does not run.
   *
   * @param then runs once every document is closed
   */
  public void closeAll(Runnable then) {
    closeNext(then);
  }

  private void closeNext(final Runnable then) {
    closeNext(null, then);
  }

  /** Closes the first document other than {@code kept}, asking when dirty, then the next. */
  private void closeNext(final OpenDocument kept, final Runnable then) {
    OpenDocument next = null;
    for (OpenDocument d : documents) {
      if (d != kept) {
        next = d;
        break;
      }
    }
    if (next == null) {
      if (kept != null && !kept.closed) {
        kept.tab.select();
      }
      if (then != null) {
        then.run();
      }
      return;
    }
    close(
        next,
        new Runnable() {
          @Override
          public void run() {
            closeNext(kept, then);
          }
        });
  }

  /** Closes every document but the selected one, asking for each dirty one; Cancel stops. */
  public void closeOthers() {
    if (active != null) {
      closeOthers(active);
    }
  }

  private void closeOthers(OpenDocument kept) {
    closeNext(kept, null);
  }

  /**
   * Selects the tab after (or before) the selected one, wrapping around.
   *
   * @param forward true for the next tab
   */
  public void selectNext(boolean forward) {
    if (documents.size() < 2 || active == null) {
      return;
    }
    int at = documents.indexOf(active);
    int next = (at + (forward ? 1 : documents.size() - 1)) % documents.size();
    select(next);
  }

  /**
   * Selects a tab.
   *
   * @param index the tab index; out of range does nothing
   */
  public void select(int index) {
    if (index >= 0 && index < documents.size()) {
      documents.get(index).tab.select();
    }
  }

  /** Cancels running loads and closes every document without asking. */
  public void dispose() {
    disposing = true;
    opener.close();
    for (OpenDocument d : new ArrayList<OpenDocument>(documents)) {
      discard(d);
    }
  }

  private void close(final OpenDocument d, final Runnable then) {
    cancelSave(d);
    if (!d.document.isDirty()) {
      discard(d);
      if (then != null) {
        then.run();
      }
      return;
    }
    d.tab.select();
    view.askUnsaved(
        d.name(),
        new Consumer<UnsavedChoice>() {
          @Override
          public void accept(UnsavedChoice choice) {
            if (d.closed) {
              return;
            }
            Runnable done =
                new Runnable() {
                  @Override
                  public void run() {
                    discard(d);
                    if (then != null) {
                      then.run();
                    }
                  }
                };
            switch (choice) {
              case SAVE -> save(d, done);
              case DISCARD -> done.run();
              case CANCEL -> {
                // The document stays open.
              }
            }
          }
        });
  }

  private void discard(OpenDocument d) {
    if (d.closed) {
      return;
    }
    d.closed = true;
    cancelSave(d);
    if (d.converting != null) {
      d.converting.cancel();
    }
    d.document.removeListener(d);
    if (d.path != null && !d.binary && !disposing) {
      closedPaths.remove(d.path);
      closedPaths.addFirst(d.path);
      if (closedPaths.size() > REOPEN_LIMIT) {
        closedPaths.removeLast();
      }
    }
    for (EditorController e : d.editors) {
      e.dispose();
    }
    budget.release(d.reserved);
    documents.remove(d);
    if (active == d) {
      active = documents.isEmpty() ? null : documents.get(documents.size() - 1);
    }
    d.tab.close();
    view.documentStateChanged();
    viewsChanged();
  }

  // Split views.

  /** How the selected document's views are arranged. */
  enum Split {
    /** One view. */
    NONE,
    /** Two views, one above the other. */
    HORIZONTAL,
    /** Two views side by side. */
    VERTICAL
  }

  /** Receives changes of the views a find highlights in. */
  interface ViewListener {
    /** The selected document, its focused view or its set of views changed. */
    void viewsChanged();

    /**
     * A view of the selected document scrolled or resized.
     *
     * @param editor the view's controller
     */
    void viewportChanged(EditorController editor);
  }

  private ViewListener viewListener;

  /** Sets the one listener for view changes. */
  void setViewListener(ViewListener listener) {
    viewListener = listener;
  }

  private void viewsChanged() {
    if (viewListener != null) {
      viewListener.viewsChanged();
    }
  }

  /** Returns the selected document's arrangement; {@link Split#NONE} without a document. */
  Split activeSplit() {
    if (active == null || active.editors.size() < 2) {
      return Split.NONE;
    }
    return active.sideBySide ? Split.VERTICAL : Split.HORIZONTAL;
  }

  /** Returns the views of the selected document, the focused one first; empty without one. */
  List<EditorController> activeEditors() {
    List<EditorController> list = new ArrayList<EditorController>();
    if (active != null) {
      list.add(active.editor);
      for (EditorController e : active.editors) {
        if (e != active.editor) {
          list.add(e);
        }
      }
    }
    return list;
  }

  /**
   * Shows the selected document in two views, side by side or one above the other; an existing
   * split takes the new arrangement. The new view starts with the focused view's selections and
   * rows and keeps its own selections, scroll position and rows from then on.
   *
   * @param sideBySide true for side by side
   */
  public void split(boolean sideBySide) {
    OpenDocument d = active;
    if (d == null) {
      return;
    }
    d.sideBySide = sideBySide;
    if (d.editors.size() > 1) {
      d.tab.setSideBySide(sideBySide);
      view.documentStateChanged();
      return;
    }
    EditorController from = d.editor;
    SelectionModel selections = d.document.newSelectionModel();
    selections.set(from.selections().selections());
    DisplayProjection projection =
        new DisplayProjection(d.document, from.projection().currentLayout());
    EditorController editor = newEditor(d, projection, selections);
    editor.attach(d.tab.split(projection, selections, editor, sideBySide));
    d.editor = editor;
    editor.setWrap(wrap);
    view.documentStateChanged();
    viewsChanged();
  }

  /** Closes every view of the selected document but the focused one. */
  public void unsplit() {
    OpenDocument d = active;
    if (d == null || d.editors.size() < 2) {
      return;
    }
    for (EditorController e : new ArrayList<EditorController>(d.editors)) {
      if (e != d.editor) {
        d.editors.remove(e);
        e.dispose();
      }
    }
    d.tab.unsplit(d.editor.view());
    view.documentStateChanged();
    viewsChanged();
  }

  /** Focuses the selected document's other view. */
  public void focusOtherView() {
    OpenDocument d = active;
    if (d == null || d.editors.size() < 2) {
      return;
    }
    d.editor = d.editors.get((d.editors.indexOf(d.editor) + 1) % d.editors.size());
    d.editor.view().focus();
    view.documentStateChanged();
    viewsChanged();
  }

  // Encodings and line endings.

  /**
   * Loads the selected document's file again, decoded with a charset, and replaces the document in
   * its tab. Does nothing for an untitled, dirty or binary document.
   *
   * @param charset the charset
   */
  public void reopenWithEncoding(Charset charset) {
    if (active == null || active.path == null || active.binary || active.document.isDirty()) {
      return;
    }
    reopening.put(active.path, charset);
    opener.open(active.path, options.withForced(charset));
  }

  /**
   * Sets the charset the selected document's next save writes; the document is dirty until then.
   * The byte order mark is kept for the same charset and dropped for another. Does nothing for a
   * binary file's view.
   *
   * @param charset the charset
   */
  public void saveWithEncoding(Charset charset) {
    if (active == null || active.binary) {
      return;
    }
    TextEncoding current = active.document.encoding();
    if (!current.charset().equals(charset)) {
      active.document.setEncoding(TextEncoding.of(charset, false));
      active.updateTitle();
      view.documentStateChanged();
    }
  }

  /**
   * Converts every line break of the selected document to one ending, as one undo step, and makes
   * it the ending Enter inserts. The new text is computed on a {@code qcaret-cpu-} worker; the
   * result is dropped when the document changed or closed meanwhile. Does nothing for a read-only
   * document.
   *
   * @param ending the ending
   */
  public void convertLineEndings(final LineEnding ending) {
    final OpenDocument d = active;
    if (d == null || d.document.isReadOnly()) {
      return;
    }
    if (!d.document.lineEndings().isMixed()
        && d.document.lineEndings().dominant(ending) == ending) {
      d.document.setLineEnding(ending);
      view.documentStateChanged();
      return;
    }
    if (d.converting != null) {
      d.converting.cancel();
    }
    final CancellationToken token = new CancellationToken();
    d.converting = token;
    d.document.clearPreedit();
    final DocumentSnapshot snapshot = d.document.snapshot();
    final long revision = d.document.revision();
    final int caret = d.editor.selections().selections().primary().caret();
    view.setActive();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              LineConversion converted = null;
              try {
                converted = LineConversion.convert(snapshot.text(), ending, caret, token);
              } catch (CancellationException e) {
                // Closed or replaced by a newer conversion.
              } finally {
                view.setIdle();
                final LineConversion result = converted;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        converted(d, token, revision, ending, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      d.converting = null;
      view.setIdle();
    }
  }

  private void converted(
      OpenDocument d,
      CancellationToken token,
      long revision,
      LineEnding ending,
      LineConversion result) {
    if (d.converting != token) {
      return;
    }
    d.converting = null;
    if (result == null
        || d.closed
        || d.document.isReadOnly()
        || d.document.revision() != revision) {
      return;
    }
    d.document.apply(
        EditBatch.of(EditBatch.Kind.COMMAND, List.of(result.replacement())),
        d.editor.selections(),
        SelectionSet.caret(result.caret()));
    d.document.setLineEnding(ending);
    d.tab.showCaret();
    view.documentStateChanged();
  }

  // Editing settings and commands.

  /** Saves every document with unsaved changes in tab order; an untitled one asks for a file. */
  public void saveAll() {
    for (OpenDocument d : new ArrayList<OpenDocument>(documents)) {
      if (d.document.isDirty() && !d.binary && !d.closed) {
        save(d, null);
      }
    }
  }

  /** Reports whether a document has unsaved changes; for {@link CommandStates}. */
  boolean anyDirty() {
    for (OpenDocument d : documents) {
      if (d.document.isDirty() && !d.binary) {
        return true;
      }
    }
    return false;
  }

  /** Opens the file closed last again; a file still open selects its tab. */
  public void reopenClosed() {
    Path path = closedPaths.pollFirst();
    if (path != null) {
      opener.open(path, options);
    }
  }

  /** Reports whether Reopen Closed Tab has a file; for {@link CommandStates}. */
  boolean canReopenClosed() {
    return !closedPaths.isEmpty();
  }

  /** Refuses or allows edits to the selected document; a binary file's view stays read-only. */
  public void toggleReadOnly() {
    if (active == null || active.binary) {
      return;
    }
    Document document = active.document;
    if (!document.isReadOnly()) {
      document.clearPreedit();
    }
    document.setReadOnly(!document.isReadOnly());
    active.updateTitle();
    view.documentStateChanged();
  }

  /**
   * Sets the selected document's columns per tab stop.
   *
   * @param columns from 1 to {@link Document#MAX_TAB_WIDTH}
   */
  public void setTabWidth(int columns) {
    if (active != null && active.document.tabWidth() != columns) {
      active.document.setTabWidth(columns);
      for (EditorController e : active.editors) {
        e.tabWidthChanged();
      }
      view.documentStateChanged();
    }
  }

  /** Switches the selected document between indenting with tabs and with spaces. */
  public void toggleInsertSpaces() {
    if (active != null) {
      active.document.setInsertSpaces(!active.document.insertSpaces());
      view.documentStateChanged();
    }
  }

  /**
   * Asks for a line, or {@code line:column} (also {@code line,column}), both from 1, and moves the
   * selected editor's caret there; other input shows a notice.
   */
  public void goToLine() {
    final OpenDocument d = active;
    if (d == null) {
      return;
    }
    view.askGoToLine(
        d.document.text().lineCount(),
        new Consumer<String>() {
          @Override
          public void accept(String input) {
            if (d.closed) {
              return;
            }
            int[] position = parsePosition(input);
            if (position.length == 0) {
              view.showNotice(UiText.GO_TO_INVALID.format(input.strip()));
            } else {
              d.editor.goTo(position[0] - 1, position[1] - 1);
            }
          }
        });
  }

  /** Parses {@code line}, {@code line:column} or {@code line,column}; empty when invalid. */
  static int[] parsePosition(String input) {
    String[] parts = input.strip().split("[:,]", -1);
    if (parts.length > 2) {
      return new int[0];
    }
    try {
      int line = Integer.parseInt(parts[0].strip());
      int column = parts.length == 2 ? Integer.parseInt(parts[1].strip()) : 1;
      return line >= 1 && column >= 1 ? new int[] {line, column} : new int[0];
    } catch (NumberFormatException e) {
      return new int[0];
    }
  }

  /**
   * Returns the input of the selected editor, for menu commands.
   *
   * @return the input, or null without a document
   */
  public EditorInput activeInput() {
    return active == null ? null : active.editor;
  }

  private static double mebibytes(long bytes) {
    return bytes / (double) (1L << 20);
  }
}
