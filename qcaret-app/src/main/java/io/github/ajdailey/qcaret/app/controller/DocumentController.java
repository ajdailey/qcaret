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
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.io.ExternalModificationException;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The documents of one window, one tab each: New, Open, Save, Save As and Close, the
 * unsaved-changes prompt and the window-wide word wrap setting. Confined to the UI thread.
 *
 * <ul>
 *   <li>Open goes through {@link DocumentOpener}: the heap-budget check, the load and the rows on a
 *       worker. A missing file opens an empty document for its path; a file already open selects
 *       its tab. A document whose text does not encode back to the file's bytes opens read-only
 *       with a notice.
 *   <li>Save writes a {@link DocumentSnapshot} through {@link DocumentFiles#save} on a {@code
 *       qcaret-io-} worker while editing goes on; the document is clean once the snapshot's state
 *       is current. A save while one runs for the same document shows a notice instead. Esc in the
 *       editor and closing the tab cancel a running save, which leaves the target unchanged when
 *       the cancel lands before the file is replaced.
 *   <li>Closing a dirty document asks Save, Don't Save or Cancel; Save closes once the save
 *       succeeds. {@link #closeAll} asks for each dirty document in tab order and stops at Cancel.
 *   <li>A closed document releases its {@link MemoryBudget} reservation.
 * </ul>
 */
public final class DocumentController {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final LoadOptions options;
  private final DocumentOpener opener;
  private final List<OpenDocument> documents = new ArrayList<OpenDocument>();
  private OpenDocument active;
  private int nextUntitled = 1;
  private boolean wrap;

  /** One open document and its tab. */
  private final class OpenDocument implements DocumentListener {
    final Document document;
    final String untitled;
    Path path;
    FileStamp baseline;
    long reserved;
    EditorController editor;
    TabView tab;
    CancellationToken saving;
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
        tab.setTitle((document.isDirty() ? "*" : "") + name, path == null ? name : path.toString());
      }
    }

    @Override
    public void textChanged(DocumentChange change) {
      // The title follows the dirty state only.
    }

    @Override
    public void dirtyChanged(boolean dirty) {
      updateTitle();
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
      d.editor.setWrap(wrap);
    }
  }

  // New and Open.

  /** Opens an empty untitled document, UTF-8 with the default line ending. */
  public void newDocument() {
    Document document =
        Document.of(
            "", TextEncoding.of(StandardCharsets.UTF_8, false), options.empty(), options.limits());
    add(document, null, null, 0, DisplayProjection.Layout.empty(document.text()));
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
    for (OpenDocument d : documents) {
      if (path.equals(d.path)) {
        budget.release(reserved);
        d.tab.select();
        return;
      }
    }
    Document document = file.document();
    OpenDocument d = add(document, path, file.stamp(), reserved, layout);
    if (!file.roundTrips()) {
      document.setReadOnly(true);
      view.showInfo(
          UiText.OPEN_TITLE.text(),
          UiText.OPEN_READ_ONLY.format(
              d.name(), file.detection().encoding().charset().displayName()));
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

  private void loadFailed(Path path, IOException error) {
    if (error instanceof NoSuchFileException && path.isAbsolute() && path.getParent() != null) {
      Document document =
          Document.of(
              "",
              TextEncoding.of(StandardCharsets.UTF_8, false),
              options.empty(),
              options.limits());
      add(document, path, null, 0, DisplayProjection.Layout.empty(document.text()));
      return;
    }
    String reason =
        error instanceof BinaryFileException
            ? UiText.OPEN_BINARY.text()
            : LogText.oneLine(error.getMessage());
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
      DisplayProjection.Layout layout) {
    final OpenDocument d = new OpenDocument(document, path, baseline, reserved);
    SelectionModel selections = document.newSelectionModel();
    DisplayProjection projection = new DisplayProjection(document, layout);
    d.editor =
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
    d.tab = view.addTab(projection, selections, d.editor, tabInput(d));
    d.editor.attach(d.tab);
    document.addListener(d);
    documents.add(d);
    active = d;
    d.updateTitle();
    if (wrap) {
      d.editor.setWrap(true);
    }
    return d;
  }

  private TabInput tabInput(final OpenDocument d) {
    return new TabInput() {
      @Override
      public void closeRequested() {
        close(d, null);
      }

      @Override
      public void activated() {
        if (!d.closed) {
          active = d;
        }
      }

      @Override
      public void moved(int index) {
        documents.remove(d);
        documents.add(Math.max(0, Math.min(index, documents.size())), d);
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
    if (d.path == null) {
      saveAs(d, then);
    } else {
      write(d, d.path, d.baseline, then);
    }
  }

  private void saveAs(final OpenDocument d, final Runnable then) {
    if (savingNotice(d)) {
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
    if (savingNotice(d)) {
      return;
    }
    final DocumentSnapshot snapshot = d.document.snapshot();
    final CancellationToken token = new CancellationToken();
    d.saving = token;
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
    if (documents.isEmpty()) {
      then.run();
      return;
    }
    close(
        documents.get(0),
        new Runnable() {
          @Override
          public void run() {
            closeNext(then);
          }
        });
  }

  /** Cancels running loads and closes every document without asking. */
  public void dispose() {
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
    d.document.removeListener(d);
    d.editor.dispose();
    budget.release(d.reserved);
    documents.remove(d);
    if (active == d) {
      active = documents.isEmpty() ? null : documents.get(documents.size() - 1);
    }
    d.tab.close();
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
