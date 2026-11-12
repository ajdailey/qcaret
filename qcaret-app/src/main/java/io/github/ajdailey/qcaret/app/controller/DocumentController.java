package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.FoldRegionSource;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.print.PrintSource;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionText;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Anchor;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.BinaryFileException;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.TextCodingException;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import io.github.ajdailey.qcaret.core.watch.FileCheck;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
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
 *       with a notice, and Save refuses it while it stays read-only. A binary file opens only after
 *       the user accepts a read-only ISO-8859-1 view; that document is never written: Save and Save
 *       As refuse it, whatever calls them.
 *   <li>Reopen with an encoding loads the file again with that charset, or with detection, and
 *       replaces the clean document in its tab once loaded; a failed load leaves the document open.
 *       Save with an encoding changes only what the next save writes. Converting the line endings
 *       computes the new text on a {@code qcaret-cpu-} worker and applies it as one undo step,
 *       dropped when the document changed meanwhile.
 *   <li>Save and reload run in {@link SaveAndReload}. Save writes a {@link DocumentSnapshot}
 *       through {@link DocumentFiles#save} on a {@code qcaret-io-} worker while editing goes on;
 *       the document is clean once the snapshot's state is current. A save while one runs for the
 *       same document shows a notice instead. Esc in the editor and closing the tab cancel a
 *       running save, which leaves the target unchanged when the cancel lands before the file is
 *       replaced; an in-place write that has begun runs to its end. Esc also cancels a running
 *       comparison. Save As onto a file another tab holds, in any window, closes that tab once the
 *       write succeeds, asking first when it has unsaved changes.
 *   <li>Each document with a file is checked through {@link ExternalChanges} on the path the user
 *       opened: a clean document reloads a changed file as one undo step, a dirty one asks Keep
 *       Mine, Reload or Compare, a deleted file leaves its buffer open, marked missing and dirty. A
 *       save that finds the file changed asks Overwrite, Reload, Compare or Cancel. Reload from
 *       Disk reads the file on demand, asking first when the document is dirty.
 *   <li>Closing a dirty document asks Save, Don't Save or Cancel; Save closes once the save
 *       succeeds. {@link #closeAll} asks for each dirty document in tab order and stops at Cancel.
 *   <li>A closed document releases its {@link MemoryBudget} reservation. The paths of the last
 *       {@value #REOPEN_LIMIT} closed files are kept for Reopen Closed Tab and File > Recently
 *       Closed, newest first; each file's caret, top line, tab settings, forced charset and chosen
 *       grammar go to the {@link FileMemory} and apply when the file opens again.
 *   <li>{@link #restore} opens a session's tab with its state; {@link #addRecovered} opens a
 *       recovered buffer dirty, and the original file is written only by a Save.
 *   <li>Tab width, indentation with spaces and read-only are per document; a binary file's view
 *       stays read-only. A document without saved state takes the width and the indentation of
 *       {@link #setTabDefaults}.
 *   <li>A tab shows its document in one view or two ({@link #split}); each view is an {@link
 *       EditorController} with its own selections and rows, and the focused one receives menu
 *       commands. Closing the tab disposes every view.
 *   <li>Each document has a {@link SyntaxHighlighter} its views paint from. Its grammar is detected
 *       on a {@code qcaret-cpu-} worker from the file name and the first line when the document
 *       opens, when a save gives it another name and when the grammars are read again, unless the
 *       user chose one ({@link #setSyntax}). The last {@value UiState#MAX_RECENT_GRAMMARS} grammars
 *       chosen, Plain Text aside, are saved in {@code syntax.recent}.
 *   <li>Bookmarks are per document, shared by its views. Word completion ({@link WordCompletion})
 *       reads every open document. Show Whitespace, Show Line Endings and the completion trigger
 *       apply to every editor of the window.
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
  private final Deque<Path> closedPaths = new ArrayDeque<Path>();
  private boolean disposing;
  private OpenDocument active;
  private int nextUntitled = 1;
  private Elsewhere elsewhere;
  private boolean wrap;
  private GrammarLibrary grammars;
  private Consumer<GrammarSet> grammarListener;
  private final WordCompletion completion;
  private final SymbolCache symbols;
  private boolean showWhitespace;
  private boolean showLineEndings;
  private int completionChars;
  private boolean autoClose = true;
  private int maxHighlightLineChars = SyntaxEngine.DEFAULT_MAX_LINE_CHARS;
  private Consumer<String> grammarChosen;
  private boolean overviewShown = true;
  private boolean lineNumbersShown = true;
  private OverviewSources overviewSources;
  private Consumer<Document> closeListener;
  private LastTab lastTab;
  private final Runnable lastClosed =
      new Runnable() {
        @Override
        public void run() {
          if (documents.isEmpty() && !disposing && lastTab != null) {
            lastTab.emptied(DocumentController.this);
          }
        }
      };
  private final ExternalChanges changes;
  private CompareController compare;
  private int defaultTabWidth = Document.DEFAULT_TAB_WIDTH;
  private boolean defaultInsertSpaces;
  private long opens;
  private final DocumentSettings declared;
  private final SaveAndReload files;
  private final SaveCopy copies;
  private final Supplier<OpenDocument> selected =
      new Supplier<OpenDocument>() {
        @Override
        public OpenDocument get() {
          return active;
        }
      };

  /**
   * One open on its way through the opener, created per {@link DocumentOpener#open} and holding
   * what that open is to do once it ends. It receives one terminal call, or none once the opener is
   * closed or the workers refuse the task; a session restore ends in each of those calls, and a
   * window does not close during a restore. A retry without the remembered encoding reuses the
   * object, so its callback, restore and given path survive.
   */
  final class Open implements DocumentOpener.Listener {
    private final long number = ++opens;

    /** Receives the tab's focused editor once the document shows, or null. */
    Consumer<EditorController> then;

    /** The session tab this open restores, or null; emptied as the restore ends. */
    Restoring restoring;

    /** The document a reopen replaces, or null for an open of a file. */
    OpenDocument reopened;

    /** The revision a reopen replaces; an edit since then keeps the document. */
    long revision;

    /** Cancels a reopen's load. */
    CancellationToken load;

    /** Skips the charset remembered for the file. */
    boolean detect;

    private Path given;
    private Charset forced;
    private boolean remembered;

    /**
     * Selects the document already open at the real path, in this window or another, which ends the
     * open unread; a reopen always reads.
     */
    @Override
    public boolean resolved(Path requested, Path path) {
      OpenDocument d = reopened == null ? find(path) : null;
      if (d != null) {
        d.tab.select();
        landed(path, d);
        finishRestore();
        return true;
      }
      if (reopened == null && elsewhere != null) {
        if (elsewhere.show(DocumentController.this, path, then)) {
          finishRestore();
          return true;
        }
      }
      if (given == null) {
        given = requested.toAbsolutePath().normalize();
      }
      return false;
    }

    /** Chooses the charset: the open's, the session's or the remembered one. */
    @Override
    public LoadOptions options(Path path, LoadOptions requested) {
      if (requested.allowBinary()) {
        return requested;
      }
      if (requested.forced() != null) {
        forced = requested.forced();
        return requested;
      }
      if (detect) {
        return requested;
      }
      Session.FileState state = memory == null ? null : memory.state(path);
      String name =
          restoring != null ? restoring.tab().encoding() : state == null ? "" : state.encoding();
      if (name.isEmpty()) {
        return requested;
      }
      Charset charset = SessionText.charset(name);
      if (charset == null) {
        LOG.warn("The remembered encoding {} is not available; detecting", LogText.oneLine(name));
        return requested;
      }
      forced = charset;
      remembered = true;
      return requested.withForced(charset);
    }

    @Override
    public void opened(Path path, LoadedFile file, DisplayProjection.Layout layout, long reserved) {
      endReopen();
      try {
        loaded(path, file, layout, reserved);
      } finally {
        finishRestore();
      }
    }

    private void loaded(
        Path path, LoadedFile file, DisplayProjection.Layout layout, long reserved) {
      int index = documents.size();
      if (reopened != null) {
        if (!path.equals(reopened.file.path) || !documents.contains(reopened)) {
          budget.release(reserved);
          return;
        }
        if (reopened.document.revision() != revision || reopened.document.isDirty()) {
          budget.release(reserved);
          reopened.tab.select();
          landed(path, reopened);
          return;
        }
        index = documents.indexOf(reopened);
        discard(reopened);
      } else {
        for (OpenDocument d : documents) {
          if (path.equals(d.file.path)) {
            budget.release(reserved);
            d.tab.select();
            landed(path, d);
            if (restoring != null) {
              applyTab(d, restoring.tab());
            }
            return;
          }
        }
      }
      Document document = file.document();
      OpenDocument d = add(document, path, file.stamp(), reserved, layout, index, file.binary());
      d.file.forced = forced;
      files.watch(d, given == null ? path : given, file.baseline());
      if (reopened == null && memory != null && restoring == null) {
        applyFile(d, memory.state(path));
      }
      landed(path, d);
      if (file.binary()) {
        document.setReadOnly(true);
        d.updateTitle();
        view.documentStateChanged();
      } else if (!file.roundTrips()) {
        files.guardBytes(d);
        view.showInfo(
            UiText.OPEN_TITLE.text(),
            UiText.OPEN_READ_ONLY.format(
                d.name(), file.detection().encoding().charset().displayName()));
        d.updateTitle();
        view.documentStateChanged();
      }
      if (restoring != null) {
        applyTab(d, restoring.tab());
      }
      if (file.settings().any()) {
        declared.apply(d, file.settings());
      }
    }

    /**
     * The file shows in a tab. The callback runs unless a later open's already reached the tab, so
     * an older open ending last does not move the caret back.
     */
    private void landed(Path path, OpenDocument d) {
      fileOpened(path);
      if (then != null && number > d.landed) {
        d.landed = number;
        then.accept(d.editor);
      }
    }

    @Override
    public void tooLarge(Path path, LoadCost cost, long available, long maxHeap) {
      endReopen();
      try {
        if (!skipRestore(path)) {
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
      } finally {
        finishRestore();
      }
    }

    /** A session's tab counts as skipped. */
    @Override
    public void canceled(Path path) {
      endReopen();
      finishRestore();
    }

    @Override
    public void failed(Path path, IOException error) {
      forced = null;
      if (remembered && error instanceof TextCodingException) {
        LOG.warn("A remembered encoding does not decode a file; detecting");
        remembered = false;
        detect = true;
        opener.open(path, options, this);
        return;
      }
      endReopen();
      try {
        if (!skipRestore(path)) {
          report(path, error);
        }
      } finally {
        finishRestore();
      }
    }

    private void report(final Path path, IOException error) {
      if (reopened == null && error instanceof BinaryFileException) {
        view.askOpenBinary(
            String.valueOf(path.getFileName()),
            new Runnable() {
              @Override
              public void run() {
                open(path, options.asBinary());
              }
            });
        return;
      }
      if (reopened == null
          && error instanceof NoSuchFileException
          && path.isAbsolute()
          && path.getParent() != null) {
        Document document =
            Document.of(
                "",
                TextEncoding.of(StandardCharsets.UTF_8, false),
                options.empty(),
                options.limits());
        OpenDocument d =
            add(
                document,
                path,
                null,
                0,
                DisplayProjection.Layout.empty(document.text()),
                documents.size(),
                false);
        files.watch(d, path.normalize(), null);
        declared.readForNewFile(d, path);
        if (then != null) {
          then.accept(d.editor);
        }
        return;
      }
      String reason = LogText.oneLine(error.getMessage());
      LOG.debug("Open failed: {}", LogText.oneLine(String.valueOf(path)));
      view.showInfo(
          UiText.OPEN_TITLE.text(),
          UiText.OPEN_FAILED.format(String.valueOf(path.getFileName()), reason));
    }

    private void endReopen() {
      if (reopened != null && reopened.reopen == this) {
        reopened.reopen = null;
      }
    }

    private void finishRestore() {
      Restoring restored = restoring;
      restoring = null;
      if (restored != null) {
        restored.done().run();
      }
    }

    /** Reports a restored file that did not open; false for any other open. */
    private boolean skipRestore(Path path) {
      if (restoring == null) {
        return false;
      }
      Path name = path.getFileName();
      restoring.failed().accept(name == null ? path.toString() : name.toString());
      return true;
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
    this.declared = new DocumentSettings(workers, ui, view);
    this.changes =
        new ExternalChanges(
            workers,
            ui,
            TimeUnit.SECONDS.toMillis(Options.DEFAULTS.pollSeconds()),
            new ExternalChanges.Listener() {
              @Override
              public void found(ExternalChanges.Watch watch, FileCheck.Result result) {
                for (OpenDocument d : documents) {
                  if (d.file.watch == watch) {
                    files.externalChange(d, result);
                  }
                }
              }
            });
    this.files =
        new SaveAndReload(
            workers,
            ui,
            view,
            budget,
            options,
            changes,
            declared,
            new SaveAndReload.Window() {
              @Override
              public void renamed(OpenDocument d) {
                detect(d);
              }

              @Override
              public OpenDocument holderOfFile(OpenDocument d, Path real) {
                return DocumentController.this.holderOfFile(d, real);
              }

              @Override
              public void closeHeld(Document held, boolean ask, Runnable ended) {
                DocumentController holder = holderOf(held);
                if (holder == null) {
                  ended.run();
                } else if (ask) {
                  holder.close(holder.entry(held), ended, ended);
                } else {
                  holder.discard(holder.entry(held));
                  ended.run();
                }
              }

              @Override
              public DocumentsUnder under() {
                return DocumentController.this.under();
              }

              @Override
              public void cleaned(String bufferId) {
                if (bufferCleaned != null) {
                  bufferCleaned.accept(bufferId);
                }
              }
            });
    this.copies =
        new SaveCopy(
            workers,
            ui,
            view,
            files,
            new SaveCopy.Window() {
              @Override
              public void copied(OpenDocument d, Path real) {
                OpenDocument other = holderOfFile(d, real);
                if (other != null) {
                  holderOf(other.document).checkFile(real);
                }
                fileOpened(real);
              }

              @Override
              public DocumentsUnder under() {
                return DocumentController.this.under();
              }
            });
    this.completion =
        new WordCompletion(
            workers,
            ui,
            new Supplier<List<Document>>() {
              @Override
              public List<Document> get() {
                return openDocuments();
              }
            });
    this.symbols = new SymbolCache(workers, ui, budget);
    this.opener =
        new DocumentOpener(
            workers,
            ui,
            budget,
            view,
            new DocumentOpener.Progress() {
              @Override
              public void progress(Path path, int files, int percent) {
                view.showLoadProgress(
                    path == null
                        ? UiText.LOAD_PROGRESS_FILES.format(files)
                        : UiText.LOAD_PROGRESS.format(path.getFileName()),
                    percent,
                    opener::cancelLoads);
              }

              @Override
              public void progressEnded() {
                view.hideLoadProgress();
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
    return active == null ? null : active.file.path;
  }

  /** Returns the selected document's state, or null without a document. */
  Document activeDocument() {
    return active == null ? null : active.document;
  }

  /** Reports whether the selected document is a binary file's read-only view. */
  boolean activeBinary() {
    return active != null && active.binary;
  }

  /** Returns the charset the user chose for the selected document, or null when it is detected. */
  Charset activeForced() {
    return active == null ? null : active.file.forced;
  }

  /** Returns the tab labels in tab order: each path, or the title of an untitled document. */
  List<String> labels() {
    List<String> labels = new ArrayList<String>();
    for (OpenDocument d : documents) {
      labels.add(d.file.path == null ? d.untitled : d.file.path.toString());
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

  /** Returns the file of the document behind a tab's input, or null without one. */
  Path pathOf(TabInput input) {
    int index = indexOf(input);
    return index < 0 ? null : documents.get(index).file.path;
  }

  /** Reports whether the document at a tab index has a file. */
  boolean hasFile(int index) {
    return index >= 0 && index < documents.size() && documents.get(index).file.path != null;
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
    sessionChanged();
  }

  // Whitespace marks, word completion and bookmarks.

  /** Reports whether the window's editors mark spaces and tabs. */
  boolean whitespaceShown() {
    return showWhitespace;
  }

  /** Reports whether the window's editors mark line breaks. */
  boolean lineEndingsShown() {
    return showLineEndings;
  }

  /**
   * Turns the whitespace or the line-ending marks on or off in every editor of the window.
   *
   * @param lineEndings true for the line-ending marks, false for the whitespace marks
   */
  public void toggleMarks(boolean lineEndings) {
    if (lineEndings) {
      showLineEndings = !showLineEndings;
    } else {
      showWhitespace = !showWhitespace;
    }
    for (OpenDocument d : documents) {
      for (EditorController e : d.editors) {
        e.setMarks(showWhitespace, showLineEndings);
      }
    }
    sessionChanged();
  }

  /**
   * Sets how many word chars typed open the completion list on their own, in every editor.
   *
   * @param chars the count, 0 for never
   */
  public void setCompletionChars(int chars) {
    completionChars = chars;
    for (OpenDocument d : documents) {
      for (EditorController e : d.editors) {
        e.setCompletionChars(chars);
      }
    }
  }

  /**
   * Turns auto-close on or off in every editor ({@code editor.autoClose}).
   *
   * @param on true when typing an opener also inserts its closer
   */
  public void setAutoClose(boolean on) {
    autoClose = on;
    for (OpenDocument d : documents) {
      for (EditorController e : d.editors) {
        e.setAutoClose(on);
      }
    }
  }

  /**
   * Sets the longest line colored; every document highlights again.
   *
   * @param chars lines longer than this stay plain ({@code editor.maxHighlightLineChars})
   */
  public void setMaxHighlightLineChars(int chars) {
    maxHighlightLineChars = chars;
    for (OpenDocument d : documents) {
      d.syntax.setMaxLineChars(chars);
    }
  }

  /**
   * Sets the interval between checks of every open file ({@code file.pollSeconds}).
   *
   * @param seconds the interval
   */
  public void setPollSeconds(int seconds) {
    changes.setPollMillis(TimeUnit.SECONDS.toMillis(seconds));
  }

  /**
   * Sets whether a changed file reloads a clean buffer without a prompt ({@code
   * file.autoReloadClean}).
   *
   * @param on false to prompt for every change
   */
  public void setAutoReloadClean(boolean on) {
    files.setAutoReloadClean(on);
  }

  /** Sets what receives a grammar's id when the user chooses it with {@link #setSyntax}. */
  void setGrammarChosen(Consumer<String> listener) {
    grammarChosen = listener;
  }

  /** Returns the open documents in tab order. */
  List<Document> openDocuments() {
    List<Document> list = new ArrayList<Document>(documents.size());
    for (OpenDocument d : documents) {
      list.add(d.document);
    }
    return list;
  }

  /** Returns the selected document's bookmarked lines, empty without a document. */
  int[] activeBookmarks() {
    return active == null ? new int[0] : active.bookmarks.lines();
  }

  /** Returns an open document's bookmarks; null when closed. */
  Bookmarks bookmarksOf(Document document) {
    OpenDocument d = entry(document);
    return d == null ? null : d.bookmarks;
  }

  /**
   * Asks for the comment of a line's bookmark and sets it, bookmarking the line when it is not; an
   * empty answer keeps the bookmark without a comment. The line is followed through edits made
   * while the prompt shows. A refused comment shows a notice and changes nothing.
   *
   * @param document an open document
   * @param line a zero-based line of it
   */
  void editBookmarkComment(Document document, int line) {
    final OpenDocument d = entry(document);
    if (d == null) {
      return;
    }
    TextSnapshot text = document.text();
    final Anchor at =
        document.anchor(text.offsetOfLine(Math.min(line, text.lineCount() - 1)), Bias.AFTER);
    String current = d.bookmarks.comment(line);
    view.askBookmarkComment(
        line,
        current == null ? "" : current,
        Bookmarks.MAX_COMMENT_LENGTH,
        new Consumer<String>() {
          @Override
          public void accept(String answer) {
            if (d.closed || at.isDisposed()) {
              return;
            }
            int target = d.document.text().lineAt(at.offset());
            at.dispose();
            if (answer == null) {
              return;
            }
            switch (d.bookmarks.setComment(target, answer)) {
              case TOO_LONG ->
                  view.showNotice(
                      UiText.BOOKMARK_COMMENT_TOO_LONG.format(Bookmarks.MAX_COMMENT_LENGTH));
              case TOO_MANY ->
                  view.showNotice(UiText.BOOKMARK_COMMENT_TOO_MANY.format(Bookmarks.MAX_COMMENTS));
              case DONE -> view.documentStateChanged();
            }
          }
        });
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
    Path directory =
        active == null || active.file.path == null ? null : active.file.path.getParent();
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
      open(path, options);
    }
  }

  private void open(Path path, LoadOptions with) {
    opener.open(path, with, new Open());
  }

  /**
   * Counts the loads still running.
   *
   * @return loads whose worker has not ended
   */
  public int loading() {
    return opener.running();
  }

  private OpenDocument add(
      Document document,
      Path path,
      FileStamp baseline,
      long reserved,
      DisplayProjection.Layout layout,
      int index,
      boolean binary) {
    document.setTabWidth(defaultTabWidth);
    document.setInsertSpaces(defaultInsertSpaces);
    final OpenDocument d =
        new OpenDocument(
            document,
            new OpenFile(path, baseline),
            reserved,
            path == null ? UiText.TAB_UNTITLED.format(untitledNumber()) : null,
            "b" + bufferNumber(),
            view,
            selected);
    d.binary = binary;
    d.syntax = highlighter(d);
    d.syntax.setRegionsListener(
        new Runnable() {
          @Override
          public void run() {
            for (EditorController e : d.editors) {
              e.syntaxRegionsChanged();
            }
          }
        });
    d.input = tabInput(d);
    d.bookmarks = new Bookmarks(document);
    SelectionModel selections = document.newSelectionModel();
    DisplayProjection projection = new DisplayProjection(document, layout);
    d.editor = newEditor(d, projection, selections);
    d.tab = view.addTab(projection, selections, d.editor, d.input, index);
    d.editor.attach(d.tab.firstView());
    d.tab.firstView().setSyntax(d.syntax);
    document.addListener(d);
    documents.add(index, d);
    active = d;
    d.updateTitle();
    if (wrap) {
      d.editor.setWrap(true);
    }
    view.documentStateChanged();
    viewsChanged();
    detect(d);
    return d;
  }

  private EditorController newEditor(
      OpenDocument d, DisplayProjection projection, SelectionModel selections) {
    EditorController editor =
        new EditorController(projection, selections, view, workers, ui, cancelSaveOf(d));
    connect(d, editor);
    d.editors.add(editor);
    editor.setSyntax(d.syntax);
    editor.setGrammar(grammar(d));
    return editor;
  }

  private Runnable cancelSaveOf(final OpenDocument d) {
    return new Runnable() {
      @Override
      public void run() {
        files.cancelSave(d);
        if (compare != null) {
          compare.cancelRunning();
        }
      }
    };
  }

  /** Routes an editor's callbacks to this window and gives it this window's editor settings. */
  private void connect(final OpenDocument d, final EditorController editor) {
    editor.selections().addListener(sessionSelections);
    editor.setViewportListener(
        new Runnable() {
          @Override
          public void run() {
            sessionChanged();
            d.syntax.viewportChanged(editor, editor.visibleStart(), editor.visibleEnd());
            if (viewListener != null && active == d) {
              viewListener.viewportChanged(editor);
            }
          }
        });
    editor.setEscapeListener(
        new Runnable() {
          @Override
          public void run() {
            if (viewListener != null && active == d) {
              viewListener.escapePressed();
            }
          }
        });
    editor.setBookmarks(d.bookmarks);
    editor.setCommentRequest(
        new IntConsumer() {
          @Override
          public void accept(int line) {
            editBookmarkComment(d.document, line);
          }
        });
    editor.setOverview(
        new OverviewUpdater(
            editor,
            d.bookmarks,
            workers,
            ui,
            new Supplier<OverviewSources>() {
              @Override
              public OverviewSources get() {
                return overviewSources;
              }
            }),
        overviewShown);
    editor.setCompletion(completion);
    editor.setCompletionChars(completionChars);
    editor.setAutoClose(autoClose);
    editor.setMarks(showWhitespace, showLineEndings);
    editor.setNoticeListener(
        new Consumer<String>() {
          @Override
          public void accept(String message) {
            view.showNotice(message);
          }
        });
    editor.setJumpListener(
        new Runnable() {
          @Override
          public void run() {
            if (jumpListener != null) {
              jumpListener.accept(editor);
            }
          }
        });
  }

  private TabInput tabInput(final OpenDocument d) {
    return new TabInput() {
      @Override
      public void closeRequested() {
        closeByUser(d);
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
      public void closeSideRequested(boolean left) {
        closeSide(d, left);
      }

      @Override
      public void copyPathRequested(boolean nameOnly) {
        if (d.file.path != null) {
          Path name = d.file.path.getFileName();
          view.setText(nameOnly && name != null ? name.toString() : d.file.path.toString());
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
      files.save(active, SaveAndReload.SaveEnd.NONE);
    }
  }

  /** Saves the selected document to a file the user picks. */
  public void saveAs() {
    if (active != null) {
      files.saveAs(active, SaveAndReload.SaveEnd.NONE);
    }
  }

  /** Writes the selected document's text to a file the user picks; the document stays as it is. */
  public void saveCopyAs() {
    if (active != null) {
      copies.saveCopy(active);
    }
  }

  /** Cancels a running save of the selected document. */
  public void cancelSave() {
    if (active != null) {
      files.cancelSave(active);
    }
  }

  /** Returns the tab of a file other than {@code d}, this window first, or null. */
  private OpenDocument holderOfFile(OpenDocument d, Path real) {
    for (OpenDocument o : documents) {
      if (o != d && o.file.path != null && o.file.path.equals(real)) {
        return o;
      }
    }
    Document document = elsewhere == null ? null : elsewhere.documentAt(real);
    if (document == null || document == d.document) {
      return null;
    }
    return holderOf(document).entry(document);
  }

  // Close.

  /** Closes the selected document, asking first when it has unsaved changes. */
  public void closeActive() {
    if (active != null) {
      closeByUser(active);
    }
  }

  private void closeByUser(OpenDocument d) {
    if (!keepsLast(d)) {
      close(d, lastClosed);
    }
  }

  private boolean keepsLast(OpenDocument d) {
    return lastTab != null && documents.size() == 1 && documents.get(0) == d && lastTab.keeps(d);
  }

  /** Close All: {@link #closeAll} as a user's close, so the last tab follows {@link LastTab}. */
  public void closeAllTabs() {
    closeNext(null, lastClosed);
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
    if (next != null && then == lastClosed && keepsLast(next)) {
      next.tab.select();
      return;
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
    closeNext(kept, lastClosed);
  }

  /** Closes the tabs on one side of a tab in tab order, asking for each dirty one; Cancel stops. */
  private void closeSide(OpenDocument from, boolean left) {
    int at = documents.indexOf(from);
    List<OpenDocument> side =
        new ArrayList<OpenDocument>(
            left ? documents.subList(0, at) : documents.subList(at + 1, documents.size()));
    closeEach(side, 0, from, active);
  }

  /** Closes a list from an index on; {@code kept} is selected once {@code selected} has closed. */
  private void closeEach(
      final List<OpenDocument> list,
      final int index,
      final OpenDocument kept,
      final OpenDocument selected) {
    if (index == list.size()) {
      if (selected.closed && !kept.closed) {
        kept.tab.select();
      }
      return;
    }
    close(
        list.get(index),
        new Runnable() {
          @Override
          public void run() {
            closeEach(list, index + 1, kept, selected);
          }
        });
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
    if (grammars != null) {
      grammars.removeListener(grammarListener);
    }
    opener.close();
    changes.close();
    completion.cancel();
    for (OpenDocument d : new ArrayList<OpenDocument>(documents)) {
      discard(d);
    }
  }

  private void close(OpenDocument d, Runnable then) {
    close(d, then, null);
  }

  /**
   * Closes a document, asking first when it has unsaved changes. Save closes it once the file is
   * written, unless an edit arrived during the write: that asks again.
   *
   * @param d the document
   * @param then runs once it closed
   * @param kept runs instead when the tab stays: a Cancel or a save that wrote no file
   */
  private void close(final OpenDocument d, final Runnable then, final Runnable kept) {
    files.cancelSave(d);
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
              if (kept != null) {
                kept.run();
              }
              return;
            }
            final Runnable done =
                new Runnable() {
                  @Override
                  public void run() {
                    discard(d);
                    if (then != null) {
                      then.run();
                    }
                  }
                };
            Runnable written =
                new Runnable() {
                  @Override
                  public void run() {
                    if (d.document.isDirty()) {
                      close(d, then, kept);
                    } else {
                      done.run();
                    }
                  }
                };
            switch (choice) {
              case SAVE -> files.save(d, new SaveAndReload.SaveEnd(written, kept));
              case DISCARD -> done.run();
              case CANCEL -> {
                if (kept != null) {
                  kept.run();
                }
              }
            }
          }
        });
  }

  private void discard(final OpenDocument d) {
    if (d.closed) {
      return;
    }
    rememberClosed(d);
    d.closed = true;
    if (closeListener != null) {
      closeListener.accept(d.document);
    }
    if (elsewhere != null) {
      elsewhere.closed(this, d.document);
    }
    files.cancelSave(d);
    if (d.reopen != null) {
      d.reopen.load.cancel();
      d.reopen = null;
    }
    if (d.file.reloading != null) {
      d.file.reloading.cancel();
    }
    if (d.converting != null) {
      d.converting.cancel();
    }
    detach(
        d,
        new Runnable() {
          @Override
          public void run() {
            if (d.file.path != null && !d.binary && !disposing) {
              closedPaths.remove(d.file.path);
              closedPaths.addFirst(d.file.path);
              if (closedPaths.size() > REOPEN_LIMIT) {
                closedPaths.removeLast();
              }
            }
            for (EditorController e : d.editors) {
              e.dispose();
            }
            d.syntax.dispose();
            d.bookmarks.dispose();
            budget.release(d.reserved);
            if (bufferCleaned != null && !disposing) {
              bufferCleaned.accept(d.bufferId);
            }
          }
        });
  }

  /**
   * Takes a closed entry out of this window, for {@link #discard} and {@link #moveTo}.
   *
   * @param d the entry
   * @param release ends the document or hands it on, after the entry stops listening to it
   */
  private void detach(OpenDocument d, Runnable release) {
    completion.documentClosed(d.document);
    symbols.documentClosed(d.document);
    if (d.file.watch != null) {
      changes.unwatch(d.file.watch);
    }
    d.document.removeListener(d);
    release.run();
    documents.remove(d);
    if (active == d) {
      active = documents.isEmpty() ? null : documents.get(documents.size() - 1);
    }
    d.tab.close();
    view.documentStateChanged();
    viewsChanged();
  }

  // HyperSearch and the overview strip.

  /**
   * Opens a file, or selects its tab, and then passes the tab's focused editor to {@code then}; a
   * load that fails, is refused or is canceled does not call it.
   *
   * @param path the file
   * @param then runs on the UI thread once the document shows
   */
  public void open(Path path, Consumer<EditorController> then) {
    OpenDocument d = find(path);
    if (d != null) {
      d.tab.select();
      then.accept(d.editor);
      return;
    }
    Open open = new Open();
    open.then = then;
    opener.open(path, options, open);
  }

  // Navigation history and Recent Files.

  private Consumer<EditorController> jumpListener;
  private Consumer<Path> openListener;

  /** Sets what runs before an editor's caret jumps; it receives that editor. */
  void setJumpListener(Consumer<EditorController> listener) {
    jumpListener = listener;
  }

  /**
   * Sets what runs when a file opens, an open one is selected by opening it or Save a Copy As
   * writes one; it gets the path.
   */
  void setOpenListener(Consumer<Path> listener) {
    openListener = listener;
  }

  private void fileOpened(Path path) {
    if (openListener != null) {
      openListener.accept(path);
    }
  }

  /** Returns the open document of a file, comparing absolute normalized paths; null when none. */
  private OpenDocument find(Path path) {
    Path wanted = path.toAbsolutePath().normalize();
    for (OpenDocument d : documents) {
      if (d.file.path != null && d.file.path.toAbsolutePath().normalize().equals(wanted)) {
        return d;
      }
    }
    return null;
  }

  /** Returns the open document of a file, or null. */
  Document documentAt(Path path) {
    OpenDocument d = find(path);
    return d == null ? null : d.document;
  }

  private OpenDocument entry(Document document) {
    for (OpenDocument d : documents) {
      if (d.document == document) {
        return d;
      }
    }
    return null;
  }

  /** Reports whether a document is open in this window. */
  boolean isOpen(Document document) {
    return entry(document) != null;
  }

  /** Returns an open document's real path, or null when untitled or closed. */
  Path pathOf(Document document) {
    OpenDocument d = entry(document);
    return d == null ? null : d.file.path;
  }

  /** Returns an open document's tab name, or null when closed. */
  String nameOf(Document document) {
    OpenDocument d = entry(document);
    return d == null ? null : d.name();
  }

  /** Reports whether an open document is a binary file's read-only view. */
  boolean binary(Document document) {
    OpenDocument d = entry(document);
    return d != null && d.binary;
  }

  /** Returns an open document's focused editor without selecting its tab; null when closed. */
  EditorController editorOf(Document document) {
    OpenDocument d = entry(document);
    return d == null ? null : d.editor;
  }

  /** Returns the window's Go to Symbol scans. */
  SymbolCache symbolCache() {
    return symbols;
  }

  /** Selects an open document's tab and returns its focused editor; null when closed. */
  EditorController show(Document document) {
    OpenDocument d = entry(document);
    if (d == null) {
      return null;
    }
    d.tab.select();
    return d.editor;
  }

  /** Sets what a user's close of the last tab does; without one the window stays empty. */
  void setLastTab(LastTab rule) {
    lastTab = rule;
  }

  /** Sets what runs as each document closes, before it releases its text. */
  void setCloseListener(Consumer<Document> listener) {
    closeListener = listener;
  }

  /** Sets where the overview strips read the find and HyperSearch matches. */
  void setOverviewSources(OverviewSources sources) {
    overviewSources = sources;
  }

  /** Recomputes every overview strip's marks, after the find query or the results changed. */
  void refreshOverview() {
    for (OpenDocument d : documents) {
      for (EditorController e : d.editors) {
        e.overview().request();
      }
    }
  }

  /** Reports whether the editors show the overview strip. */
  boolean overviewShown() {
    return overviewShown;
  }

  /** Shows or hides the overview strip in every editor of the window. */
  public void toggleOverview() {
    overviewShown = !overviewShown;
    for (OpenDocument d : documents) {
      for (EditorController e : d.editors) {
        e.setOverviewShown(overviewShown);
      }
    }
    sessionChanged();
  }

  /** Reports whether the window's editors show line numbers. */
  boolean lineNumbersShown() {
    return lineNumbersShown;
  }

  /** Shows or hides the line numbers in every editor of the window. */
  public void toggleLineNumbers() {
    lineNumbersShown = !lineNumbersShown;
    view.setLineNumbersShown(lineNumbersShown);
    sessionChanged();
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

  /** Receives changes of the views a find highlights in, and Esc in those views. */
  interface ViewListener {
    /** The selected document, its focused view or its set of views changed. */
    void viewsChanged();

    /**
     * A view of the selected document scrolled or resized.
     *
     * @param editor the view's controller
     */
    void viewportChanged(EditorController editor);

    /** Esc in a view of the selected document, with one caret and no block. */
    void escapePressed();
  }

  private ViewListener viewListener;
  private final List<Runnable> activeListeners = new ArrayList<Runnable>();

  /** Sets the one listener for view changes. */
  void setViewListener(ViewListener listener) {
    viewListener = listener;
  }

  /** Adds what runs when the selected document, its focused view or its set of views changed. */
  void addActiveListener(Runnable listener) {
    activeListeners.add(listener);
  }

  /** Removes a listener {@link #addActiveListener} added. */
  void removeActiveListener(Runnable listener) {
    activeListeners.remove(listener);
  }

  private void viewsChanged() {
    sessionChanged();
    if (viewListener != null) {
      viewListener.viewsChanged();
    }
    for (Runnable listener : new ArrayList<Runnable>(activeListeners)) {
      listener.run();
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
    if (active != null) {
      split(active, sideBySide);
    }
  }

  private void split(OpenDocument d, boolean sideBySide) {
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
    editor.view().setSyntax(d.syntax);
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
        d.syntax.removeView(e);
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
   * its tab. Does nothing for an untitled, dirty or binary document, or during another reopen of
   * it. Closing the tab cancels the load; an edit or a Save As during the load keeps the document
   * and drops the load; a failed load shows a notice and keeps the document.
   *
   * @param charset the charset
   */
  public void reopenWithEncoding(Charset charset) {
    if (canReopen()) {
      reopen(options.withForced(charset), false);
    }
  }

  /**
   * Loads the selected document's file again with encoding detection and replaces the document in
   * its tab, past a charset forced or remembered for the file. Does nothing where {@link
   * #reopenWithEncoding} does nothing, and a running reopen ends the same way.
   */
  public void reopenWithDetection() {
    if (canReopen()) {
      reopen(options, true);
    }
  }

  private void reopen(LoadOptions with, boolean detect) {
    Open open = new Open();
    open.reopened = active;
    open.given = active.file.given;
    open.revision = active.document.revision();
    open.detect = detect;
    active.reopen = open;
    open.load = opener.open(active.file.path, with, open);
  }

  private boolean canReopen() {
    return active != null
        && active.file.path != null
        && !active.binary
        && !active.document.isDirty()
        && active.reopen == null;
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
    active.file.forced = charset;
    TextEncoding current = active.document.encoding();
    if (!current.charset().equals(charset)) {
      active.document.setEncoding(TextEncoding.of(charset, false));
      active.updateTitle();
      view.documentStateChanged();
    }
  }

  /**
   * Converts every line break of the selected document to one ending, as one undo step, and makes
   * it the ending Enter inserts. The new text is computed on a {@code qcaret-cpu-} worker and
   * applied on the UI thread, with every view of the document busy until the apply; the result is
   * dropped when the document changed or closed meanwhile. Does nothing for a read-only document.
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
    busy(d, true);
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
                final LineConversion result = converted;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        try {
                          converted(d, token, revision, ending, result);
                        } finally {
                          view.setIdle();
                          if (d.converting == null) {
                            busy(d, false);
                          }
                        }
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      d.converting = null;
      view.setIdle();
      busy(d, false);
    }
  }

  /** Shows or clears the busy cursor over every view of a document. */
  private static void busy(OpenDocument d, boolean on) {
    for (EditorController e : d.editors) {
      if (e.view() != null) {
        e.view().setBusy(on);
      }
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
        files.save(d, SaveAndReload.SaveEnd.NONE);
      }
    }
  }

  /** Reports whether Save refuses the selected document; for {@link CommandStates}. */
  boolean activeRefusesSave() {
    return active != null && SaveAndReload.refusesSave(active);
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
      open(path, options);
    }
  }

  /**
   * Opens a file of File > Recently Closed; a file still open selects its tab.
   *
   * @param index the entry, newest first; out of range does nothing
   */
  public void reopenClosed(int index) {
    List<Path> paths = new ArrayList<Path>(closedPaths);
    if (index >= 0 && index < paths.size()) {
      closedPaths.remove(paths.get(index));
      open(paths.get(index), options);
    }
  }

  /**
   * Opens every readable file of a list, in list order, and names the others in a notice.
   *
   * @param paths the files
   * @param opened receives the files opened, or null
   */
  void openAll(List<Path> paths, Consumer<List<Path>> opened) {
    OpenAll.open(workers, ui, view, this, paths, opened);
  }

  /** Opens every file of File > Recently Closed; the opened ones leave the list. */
  public void openAllClosed() {
    openAll(
        new ArrayList<Path>(closedPaths),
        new Consumer<List<Path>>() {
          @Override
          public void accept(List<Path> opened) {
            closedPaths.removeAll(opened);
            view.documentStateChanged();
            sessionChanged();
          }
        });
  }

  /** Empties File > Recently Closed. */
  public void clearClosed() {
    closedPaths.clear();
    view.documentStateChanged();
    sessionChanged();
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
    active.file.readOnlyByGuard = false;
    active.updateTitle();
    view.documentStateChanged();
    sessionChanged();
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

  /** Asks for the selected document's columns per tab stop, from 1 to the largest accepted. */
  public void askTabWidth() {
    final OpenDocument d = active;
    if (d == null) {
      return;
    }
    view.askTabWidth(
        d.document.tabWidth(),
        Document.MAX_TAB_WIDTH,
        new IntConsumer() {
          @Override
          public void accept(int columns) {
            if (active == d) {
              setTabWidth(columns);
            }
          }
        });
  }

  /**
   * Sets what a document without saved state starts with; open documents keep theirs.
   *
   * @param columns the columns per tab stop, from 1 to {@link Document#MAX_TAB_WIDTH}
   * @param insertSpaces true to indent with spaces
   */
  public void setTabDefaults(int columns, boolean insertSpaces) {
    defaultTabWidth = columns;
    defaultInsertSpaces = insertSpaces;
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

  // Syntax.

  /**
   * Connects the grammars: every document's grammar is detected again whenever they are read; a
   * grammar the user chose stays while the new grammars have it.
   *
   * @param library the grammars
   */
  public void setGrammars(GrammarLibrary library) {
    grammars = library;
    grammarListener =
        new Consumer<GrammarSet>() {
          @Override
          public void accept(GrammarSet set) {
            for (OpenDocument d : documents) {
              if (d.manualGrammar && set.get(d.grammar) != null) {
                applyGrammar(d, d.grammar, set);
              } else {
                d.manualGrammar = false;
                detect(d);
              }
            }
          }
        };
    library.addListener(grammarListener);
  }

  /**
   * Uses a grammar for the selected document from now on, whatever its file name.
   *
   * @param id the grammar's id; an unknown id does nothing
   */
  public void setSyntax(String id) {
    GrammarSet set = grammars == null ? null : grammars.set();
    if (active != null && set != null && set.get(id) != null) {
      active.manualGrammar = true;
      applyGrammar(active, id, set);
      if (grammarChosen != null) {
        grammarChosen.accept(id);
      }
    }
  }

  /** Returns the selected document's grammar, or null without a document or before the read. */
  Grammar activeGrammar() {
    GrammarSet set = grammars == null ? null : grammars.set();
    return active == null || set == null ? null : set.get(active.grammar);
  }

  /** Returns the grammars, plain text first, then by name; empty before they are read. */
  List<Grammar> grammarChoices() {
    GrammarSet set = grammars == null ? null : grammars.set();
    return set == null ? List.of() : set.byName();
  }

  /**
   * Returns the fold regions of the selected document's grammar.
   *
   * @return the source, or null without a document
   */
  public FoldRegionSource activeFoldRegions() {
    return active == null ? null : active.syntax;
  }

  private SyntaxHighlighter highlighter(final OpenDocument d) {
    return new SyntaxHighlighter(
        d.document,
        workers,
        ui,
        new Runnable() {
          @Override
          public void run() {
            for (EditorController e : d.editors) {
              if (e.view() != null) {
                e.view().redrawHighlights();
              }
            }
          }
        },
        maxHighlightLineChars);
  }

  /** Picks the document's grammar on a worker from its file name and first line. */
  private void detect(final OpenDocument d) {
    final GrammarSet set = grammars == null ? null : grammars.set();
    if (set == null || d.manualGrammar || d.closed) {
      return;
    }
    Path file = d.file.path == null ? null : d.file.path.getFileName();
    final String name = file == null ? null : file.toString();
    final TextSnapshot text = d.document.text();
    final CancellationToken token = new CancellationToken();
    d.detecting++;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              int length = Math.min(text.lineLength(0), GrammarSet.FIRST_LINE_CHARS);
              final String id = set.detect(name, text.subSequence(0, length), token);
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      d.detecting--;
                      if (!d.closed && !d.manualGrammar && currentGrammars() == set) {
                        applyGrammar(d, id, set);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      d.detecting--;
      LOG.debug("Syntax not detected: workers are closed");
    }
  }

  private void applyGrammar(OpenDocument d, String id, GrammarSet set) {
    d.grammar = id;
    d.syntax.setSyntax(GrammarSet.PLAIN.equals(id) ? null : set.syntax(id));
    for (EditorController e : d.editors) {
      e.setGrammar(grammar(d));
    }
    view.documentStateChanged();
  }

  private GrammarSet currentGrammars() {
    return grammars == null ? null : grammars.set();
  }

  /** Returns the document's grammar, or null for plain text and before the grammars are read. */
  private Grammar grammar(OpenDocument d) {
    GrammarSet set = grammars == null ? null : grammars.set();
    return set == null || GrammarSet.PLAIN.equals(d.grammar) ? null : set.get(d.grammar);
  }

  private static double mebibytes(long bytes) {
    return bytes / (double) (1L << 20);
  }

  // External changes.

  /** Sets the controller that shows the prompt's Compare. */
  void setCompare(CompareController value) {
    compare = value;
    files.setCompare(value);
  }

  /** Returns the options files load with. */
  LoadOptions loadOptions() {
    return options;
  }

  /** Returns the path the user opened the selected document by, or null without a file. */
  Path activeOpenedPath() {
    return active == null || active.file.path == null ? null : active.file.given;
  }

  /** Checks every open file for changes by other programs now, as on window activation. */
  public void checkFiles() {
    changes.checkAll();
  }

  /**
   * Checks the file of an open document now; a file not open is ignored.
   *
   * @param path the file's real path
   */
  void checkFile(Path path) {
    OpenDocument d = find(path);
    if (d != null && d.file.watch != null) {
      changes.check(d.file.watch);
    }
  }

  /** Reports whether the monitor's thread runs; for tests. */
  boolean watching() {
    return changes.running();
  }

  /** Counts the finished checks of every due file; for tests. */
  long checkPasses() {
    return changes.passes();
  }

  /**
   * Reads the selected document's file again, as an external change does; a dirty document asks
   * first, and undo returns to the text before the reload. A file known to be deleted shows a
   * notice.
   */
  public void reloadFromDisk() {
    if (active != null) {
      files.reloadFromDisk(active);
    }
  }

  // Multiple windows and the command line.

  /** What a window's documents reach of the other windows. */
  interface Elsewhere {
    /**
     * Selects a file's tab in another window and raises it.
     *
     * @param asking the window opening the file
     * @param path the file's real path
     * @param then receives the tab's focused editor, or null
     * @return false when no other window holds the file
     */
    boolean show(DocumentController asking, Path path, Consumer<EditorController> then);

    /** Returns the window holding a document, or null when it is closed. */
    DocumentController holderOf(Document document);

    /** Returns the document of a file in any window, or null. */
    Document documentAt(Path path);

    /** Returns the open documents of every window, {@code asking}'s first. */
    List<Document> documents(DocumentController asking);

    /** Raises a window. */
    void raise(DocumentController holder);

    /** A document closed in a window. */
    void closed(DocumentController holder, Document document);

    /** Returns the number of the next untitled document of the instance. */
    int nextUntitled();

    /** Returns the number of the next buffer of the instance; a recovery file is named by it. */
    int nextBuffer();
  }

  /** Connects this window to the other windows of its instance. */
  void setElsewhere(Elsewhere value) {
    elsewhere = value;
  }

  private int untitledNumber() {
    return elsewhere == null ? nextUntitled++ : elsewhere.nextUntitled();
  }

  private int bufferNumber() {
    return elsewhere == null ? nextBuffer++ : elsewhere.nextBuffer();
  }

  /**
   * Opens a file with its command-line options. {@code +<line>[:<column>]} and {@code -r} also
   * apply to a file already open; {@code -e} applies only to a file read now.
   *
   * @param file the file and its options
   */
  public void open(final FileRequest file) {
    Consumer<EditorController> then =
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            placed(editor, file);
          }
        };
    OpenDocument d = find(file.path());
    if (d != null) {
      d.tab.select();
      then.accept(d.editor);
      return;
    }
    Open open = new Open();
    open.then = then;
    opener.open(
        file.path(),
        file.encoding() == null ? options : options.withForced(Charset.forName(file.encoding())),
        open);
  }

  private void placed(EditorController editor, FileRequest file) {
    if (file.readOnly()) {
      setReadOnly(editor.document());
    }
    if (file.line() > 0) {
      editor.goTo(file.line() - 1, Math.max(0, file.column() - 1));
    }
  }

  /** Refuses edits to a document in any window. */
  private void setReadOnly(Document document) {
    DocumentController holder = holderOf(document);
    if (holder != null && !document.isReadOnly()) {
      document.clearPreedit();
      document.setReadOnly(true);
      holder.entry(document).updateTitle();
      holder.view.documentStateChanged();
      holder.sessionChanged();
    }
  }

  /**
   * Selects the tab of a file this window holds, as an open that found it open does.
   *
   * @param path the file's real path
   * @param then receives the tab's focused editor, or null
   * @return false when this window does not hold the file
   */
  boolean showOpen(Path path, Consumer<EditorController> then) {
    OpenDocument d = find(path);
    if (d == null) {
      return false;
    }
    d.tab.select();
    fileOpened(path);
    if (then != null) {
      then.accept(d.editor);
    }
    return true;
  }

  /** Returns the window holding a document, this one first; null when it is closed. */
  DocumentController holderOf(Document document) {
    if (isOpen(document)) {
      return this;
    }
    return elsewhere == null ? null : elsewhere.holderOf(document);
  }

  /** Returns the document of a file in this window or another, or null. */
  Document documentAnywhere(Path path) {
    Document document = documentAt(path);
    return document != null || elsewhere == null ? document : elsewhere.documentAt(path);
  }

  /** Returns the open documents of every window, this window's first, each in tab order. */
  List<Document> documentsAnywhere() {
    return elsewhere == null ? openDocuments() : elsewhere.documents(this);
  }

  // Workspace browser file operations.

  private DocumentsUnder under;
  private Consumer<String> bufferMoved;

  /** Returns this window's documents as the workspace browser's Rename and Delete reach them. */
  DocumentsUnder under() {
    if (under == null) {
      under =
          new DocumentsUnder(
              changes,
              closedPaths,
              view,
              documents,
              new DocumentsUnder.Moved() {
                @Override
                public void moved(OpenDocument d, boolean renamed) {
                  if (renamed) {
                    detect(d);
                  }
                  declared.readForMove(d);
                  if (bufferMoved != null) {
                    bufferMoved.accept(d.bufferId);
                  }
                  d.updateTitle();
                }
              },
              new Runnable() {
                @Override
                public void run() {
                  sessionChanged();
                }
              });
    }
    return under;
  }

  /** Sets what receives a buffer's id when a rename moved its file. */
  void setBufferMoved(Consumer<String> listener) {
    bufferMoved = listener;
  }

  /** Selects a document's tab in the window holding it, raises that window, returns the editor. */
  EditorController showAnywhere(Document document) {
    DocumentController holder = holderOf(document);
    if (holder == null) {
      return null;
    }
    if (holder != this && elsewhere != null) {
      elsewhere.raise(holder);
    }
    return holder.show(document);
  }

  /**
   * Captures what printing the selected document reads: a text snapshot, so later edits do not
   * reach the job, and the selections of the active view. Word wrap and folds are view state and
   * stay out of it. Line numbers follow the window's setting.
   *
   * @return the source, or null without a document
   */
  PrintSource printSource() {
    if (active == null) {
      return null;
    }
    List<Selection> selections = active.editor.selections().selections().selections();
    int[] ranges = new int[selections.size() * 2];
    int count = 0;
    for (Selection selection : selections) {
      if (!selection.isEmpty()) {
        ranges[count++] = selection.start();
        ranges[count++] = selection.end();
      }
    }
    return new PrintSource(
        active.name(),
        active.document.text(),
        Arrays.copyOf(ranges, count),
        active.document.tabWidth(),
        lineNumbersShown,
        active.syntax == null ? null : active.syntax.syntax(),
        maxHighlightLineChars);
  }

  /** Returns the selected document's tab name, or null without a document. */
  String activeName() {
    return active == null ? null : active.name();
  }

  /** Returns the document behind a tab's input, or null when it is closed. */
  Document documentOf(TabInput input) {
    int index = indexOf(input);
    return index < 0 ? null : documents.get(index).document;
  }

  /**
   * Reports whether an open document's tab can move: no worker or prompt holds its entry here. Its
   * save, conversion and reload deliver to this window, and so does a change prompt's answer.
   */
  boolean canMove(Document document) {
    OpenDocument d = entry(document);
    return d != null && !d.file.running() && d.converting == null && d.reopen == null;
  }

  /**
   * Moves a tab to another window with its live document: text, undo history, dirty state, carets,
   * scroll, folds, bookmarks, grammar, read-only state and file watch. Nothing is read again. This
   * window's entry ends as closed, so a prompt or a worker result still holding it drops out.
   *
   * @param document a document for which {@link #canMove} holds
   * @param target the receiving window's documents
   * @param index the tab position there, clamped to the tabs
   */
  void moveTo(Document document, DocumentController target, int index) {
    final OpenDocument from = entry(document);
    int[] rows = new int[from.editors.size()];
    for (int i = 0; i < rows.length; i++) {
      EditorController e = from.editors.get(i);
      rows[i] = e.view() == null ? 0 : e.view().topRow();
    }
    FileBaseline baseline = from.file.watch == null ? null : from.file.watch.baseline();
    from.closed = true;
    detach(
        from,
        new Runnable() {
          @Override
          public void run() {
            for (EditorController e : from.editors) {
              e.selections().removeListener(sessionSelections);
              e.releaseView();
            }
          }
        });
    target.adopt(from, baseline, rows, Math.max(0, Math.min(index, target.documents.size())));
  }

  private void adopt(OpenDocument from, FileBaseline baseline, final int[] rows, int index) {
    final OpenDocument d = new OpenDocument(from, view, selected);
    d.input = tabInput(d);
    EditorController focused = from.editor;
    for (EditorController e : d.editors) {
      e.rebind(view, cancelSaveOf(d));
      connect(d, e);
    }
    EditorController first = d.editors.get(0);
    d.tab = view.addTab(first.projection(), first.selections(), first, d.input, index);
    first.attach(d.tab.firstView());
    for (int i = 1; i < d.editors.size(); i++) {
      EditorController e = d.editors.get(i);
      e.attach(d.tab.split(e.projection(), e.selections(), e, d.sideBySide));
    }
    for (EditorController e : d.editors) {
      e.view().setSyntax(d.syntax);
      e.setWrap(wrap);
    }
    d.editor = focused;
    d.document.addListener(d);
    documents.add(index, d);
    active = d;
    if (d.file.given != null) {
      d.file.watch = changes.watch(d.file.given, baseline);
      d.file.watch.setMissing(d.file.missing);
    }
    d.updateTitle();
    focused.view().focus();
    view.documentStateChanged();
    viewsChanged();
    if (from.detecting > 0) {
      detect(d);
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!d.closed) {
              for (int i = 0; i < Math.min(rows.length, d.editors.size()); i++) {
                d.editors.get(i).view().scrollToRow(rows[i]);
              }
            }
          }
        });
  }

  // Session and recovery.

  /** Where a closed file's state goes and where an opening file's comes from. */
  interface FileMemory {
    /**
     * Returns a file's state.
     *
     * @param path the file's real path
     * @return the state, or null when none is kept
     */
    Session.FileState state(Path path);

    /**
     * Keeps a closing file's state.
     *
     * @param state the state
     */
    void remember(Session.FileState state);
  }

  /**
   * An open buffer as recovery writes it.
   *
   * @param id the buffer's id
   * @param snapshot its text and encoding
   * @param path the path the user opened, or null for an untitled document
   * @param title the untitled title, or null
   * @param base what the buffer last matched on disk, or null
   * @param language the grammar the user chose, empty for detection
   * @param selections the focused view's selections
   */
  record Buffer(
      String id,
      DocumentSnapshot snapshot,
      Path path,
      String title,
      FileBaseline base,
      String language,
      String selections) {}

  /**
   * A recovered buffer read on a worker.
   *
   * @param header the file's header
   * @param document the document, clean and not yet bound to a thread
   * @param layout its rows without wrap
   * @param real the file's real path, or null for an untitled buffer
   * @param given the path the user opened, or null for an untitled buffer
   * @param base what the buffer matched on disk; null for a missing file
   * @param reserved bytes reserved for the document
   */
  record Recovered(
      RecoveryFile.Header header,
      Document document,
      DisplayProjection.Layout layout,
      Path real,
      Path given,
      FileBaseline base,
      long reserved) {}

  /** A session's tab on its way through the opener. */
  private record Restoring(Session.Tab tab, Consumer<String> failed, Runnable done) {}

  private int nextBuffer = 1;
  private FileMemory memory;
  private Consumer<String> bufferCleaned;
  private Runnable sessionListener;
  private final SelectionListener sessionSelections =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          sessionChanged();
        }
      };

  /** Sets where file state is kept. */
  void setFileMemory(FileMemory value) {
    memory = value;
  }

  /** Sets what receives a buffer's id when it has no unsaved changes left or it closes. */
  void setBufferCleaned(Consumer<String> listener) {
    bufferCleaned = listener;
  }

  /** Sets what runs when something the session holds changed. */
  void setSessionListener(Runnable listener) {
    sessionListener = listener;
  }

  /** Reports a change of something the session holds. */
  void sessionChanged() {
    if (sessionListener != null) {
      sessionListener.run();
    }
  }

  /**
   * Opens a session's tab after the tabs open now, with its state.
   *
   * @param tab the tab, which names a file
   * @param failed receives the file's name when it does not open
   * @param done runs on the UI thread once the tab is open or was skipped
   */
  void restore(Session.Tab tab, Consumer<String> failed, Runnable done) {
    Path path;
    try {
      path = Path.of(tab.path());
    } catch (InvalidPathException e) {
      failed.accept(tab.path());
      done.run();
      return;
    }
    Open open = new Open();
    open.restoring = new Restoring(tab, failed, done);
    opener.open(path, tab.binary() ? options.asBinary() : options, open);
  }

  /**
   * Reads the settings files declare from these sources from now on; the first call turns the
   * reading on. Open documents keep theirs.
   *
   * @param editorConfig true to read {@code .editorconfig} files
   * @param modelines true to read modelines
   */
  void setFileSettings(boolean editorConfig, boolean modelines) {
    FileSettingsReader created = declared.setSources(editorConfig, modelines);
    if (created != null) {
      opener.setSettings(created);
    }
  }

  /** Uses a remembered grammar; a grammar the library lacks falls back to detection. */
  private void restoreGrammar(OpenDocument d, String id) {
    if (id.isEmpty()) {
      return;
    }
    GrammarSet set = currentGrammars();
    d.manualGrammar = true;
    d.grammar = id;
    if (set != null && set.get(id) != null) {
      applyGrammar(d, id, set);
    } else if (set != null) {
      d.manualGrammar = false;
      d.grammar = GrammarSet.PLAIN;
      detect(d);
    }
  }

  private void applyFile(OpenDocument d, Session.FileState state) {
    if (state == null) {
      return;
    }
    d.setTabSettings(state.tabWidth(), state.insertSpaces());
    restoreGrammar(d, state.language());
    SelectionSet set = SessionText.selections(state.selections(), d.document.text().length());
    d.editor.restoreView(set, state.top(), new int[0]);
    view.documentStateChanged();
  }

  private void applyTab(OpenDocument d, Session.Tab tab) {
    d.setTabSettings(tab.tabWidth(), tab.insertSpaces());
    restoreGrammar(d, tab.language());
    int lines = d.document.text().lineCount();
    for (int line : SessionText.numbers(tab.bookmarks())) {
      if (line < lines && !d.bookmarks.contains(line)) {
        d.bookmarks.toggle(line);
      }
    }
    for (Session.Comment comment : tab.comments()) {
      if (comment.line() < lines) {
        d.bookmarks.setComment(comment.line(), comment.text());
      }
    }
    if (tab.views().size() > 1) {
      if (active != d) {
        d.tab.select();
      }
      split(d, tab.sideBySide());
      int[] weights = SessionText.numbers(tab.weights());
      if (weights.length == 2 && weights[0] > 0 && weights[1] > 0) {
        d.tab.setSplitWeights(weights);
      }
    }
    for (int i = 0; i < Math.min(tab.views().size(), d.editors.size()); i++) {
      Session.View saved = tab.views().get(i);
      SelectionSet set = SessionText.selections(saved.selections(), d.document.text().length());
      d.editors.get(i).restoreView(set, saved.top(), SessionText.numbers(saved.folds()));
    }
    if (d.editors.size() > 1) {
      d.editor = d.editors.get(Math.min(tab.focused(), d.editors.size() - 1));
      d.editor.view().focus();
    }
    if (tab.readOnly() && !d.document.isReadOnly()) {
      d.document.clearPreedit();
      d.document.setReadOnly(true);
      d.updateTitle();
    }
    view.documentStateChanged();
  }

  private Session.Tab tab(OpenDocument d, String buffer) {
    List<Session.View> views = new ArrayList<Session.View>(2);
    for (EditorController e : d.editors) {
      views.add(
          new Session.View(
              SessionText.selections(e.selections().selections()),
              e.topLine(),
              SessionText.numbers(e.projection().collapsedLines())));
    }
    List<Session.Comment> comments = new ArrayList<Session.Comment>();
    for (int line : d.bookmarks.commentedLines()) {
      comments.add(new Session.Comment(line, d.bookmarks.comment(line)));
    }
    Path file = d.file.target();
    return new Session.Tab(
        file == null ? "" : file.toString(),
        d.untitled == null ? "" : d.untitled,
        buffer,
        d.file.forced == null ? "" : d.file.forced.name(),
        d.manualGrammar ? d.grammar : "",
        d.binary,
        d.document.tabWidth(),
        d.document.insertSpaces(),
        views,
        Math.max(0, d.editors.indexOf(d.editor)),
        d.sideBySide,
        d.editors.size() > 1 ? SessionText.numbers(d.tab.splitWeights()) : "",
        SessionText.numbers(d.bookmarks.lines()),
        comments,
        d.document.isReadOnly());
  }

  private void rememberClosed(OpenDocument d) {
    if (d.file.path == null) {
      return;
    }
    if (memory != null && !d.binary && !disposing) {
      EditorController e = d.editor;
      memory.remember(
          new Session.FileState(
              d.file.path.toString(),
              d.file.forced == null ? "" : d.file.forced.name(),
              d.manualGrammar ? d.grammar : "",
              d.document.tabWidth(),
              d.document.insertSpaces(),
              SessionText.selections(e.selections().selections()),
              e.topLine()));
    }
  }

  /** Returns the documents with unsaved changes, in tab order. */
  List<Document> dirtyDocuments() {
    List<Document> list = new ArrayList<Document>();
    for (OpenDocument d : documents) {
      if (d.document.isDirty()) {
        list.add(d.document);
      }
    }
    return list;
  }

  /** Selects an open document's tab and asks about its unsaved changes; closes nothing. */
  void askBeforeQuit(Document document, Consumer<UnsavedChoice> answer) {
    OpenDocument d = entry(document);
    d.tab.select();
    view.askUnsaved(d.name(), answer);
  }

  /**
   * Selects an open document's tab and saves it, in place of a save that runs.
   *
   * @param document the document
   * @param saved runs once the file is written
   * @param stopped runs when no file was written: a failure, or a canceled chooser or prompt
   */
  void saveForQuit(Document document, Runnable saved, Runnable stopped) {
    OpenDocument d = entry(document);
    files.cancelSave(d);
    d.tab.select();
    files.save(d, new SaveAndReload.SaveEnd(saved, stopped));
  }

  /** Selects an open document's tab; a closed document selects nothing. */
  void reselect(Document document) {
    OpenDocument d = entry(document);
    if (d != null) {
      d.tab.select();
    }
  }

  /** Closes every document without asking; each file's state goes to the {@link FileMemory}. */
  void closeForQuit() {
    for (OpenDocument d : new ArrayList<OpenDocument>(documents)) {
      discard(d);
    }
  }

  /**
   * Returns the tabs a session holds: every document with a file. With {@code buffers} true, each
   * dirty document also holds its snapshot buffer and an untitled one is included.
   *
   * @param buffers true when the dirty buffers were written as snapshots
   * @return the tabs
   */
  List<Session.Tab> sessionTabs(boolean buffers) {
    List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    for (OpenDocument d : documents) {
      boolean snapshot = buffers && d.document.isDirty() && !d.binary;
      if (d.file.path != null || snapshot) {
        tabs.add(tab(d, snapshot ? d.bufferId : ""));
      }
    }
    return tabs;
  }

  /** Returns the selected tab's index in {@link #sessionTabs}, -1 when it is not there. */
  int sessionActive(boolean buffers) {
    int index = 0;
    for (OpenDocument d : documents) {
      if (d == active) {
        return d.file.path != null || (buffers && d.document.isDirty()) ? index : -1;
      }
      if (d.file.path != null || (buffers && d.document.isDirty() && !d.binary)) {
        index++;
      }
    }
    return -1;
  }

  /** Returns the window's display state; the Bookmarks list is {@link BookmarkList}'s. */
  Session.Display sessionDisplay(boolean bookmarks) {
    return new Session.Display(
        wrap, showWhitespace, showLineEndings, overviewShown, bookmarks, lineNumbersShown);
  }

  /** Sets word wrap, the whitespace and line-ending marks, the overview strip and line numbers. */
  void restoreDisplay(Session.Display display) {
    if (display.wrap() != wrap) {
      toggleWrap();
    }
    if (display.whitespace() != showWhitespace) {
      toggleMarks(false);
    }
    if (display.lineEndings() != showLineEndings) {
      toggleMarks(true);
    }
    if (display.overview() != overviewShown) {
      toggleOverview();
    }
    if (display.lineNumbers() != lineNumbersShown) {
      toggleLineNumbers();
    }
  }

  /** Returns Reopen Closed Tab's files, newest first. */
  List<String> closedFiles() {
    List<String> list = new ArrayList<String>();
    for (Path path : closedPaths) {
      list.add(path.toString());
    }
    return list;
  }

  /** Sets Reopen Closed Tab's files, newest first; a path that does not parse is left out. */
  void restoreClosed(List<String> files) {
    for (String file : files) {
      try {
        if (closedPaths.size() < REOPEN_LIMIT) {
          closedPaths.addLast(Path.of(file));
        }
      } catch (InvalidPathException e) {
        LOG.debug("A closed file's path does not parse");
      }
    }
  }

  /**
   * Returns every buffer with unsaved changes, a binary file's view aside.
   *
   * @return the buffers, each with a snapshot taken now
   */
  List<Buffer> dirtyBuffers() {
    List<Buffer> buffers = new ArrayList<Buffer>();
    for (OpenDocument d : documents) {
      if (d.document.isDirty() && !d.binary) {
        Path file = d.file.target();
        buffers.add(
            new Buffer(
                d.bufferId,
                d.document.snapshot(),
                file,
                d.untitled,
                d.file.watch == null ? null : d.file.watch.baseline(),
                d.manualGrammar ? d.grammar : "",
                SessionText.selections(d.editor.selections().selections())));
      }
    }
    return buffers;
  }

  /**
   * Opens a recovered buffer dirty; the original file is not written. The file on disk becomes its
   * modified-line baseline ({@link RecoveredBaseline}). Its watch baseline is the one the buffer
   * was based on, so a file that changed since shows the change prompt and a save asks first. A
   * clean tab of the file gives way to the buffer.
   *
   * @param r the buffer
   * @param tab the session's tab, or null to take the selections from the header
   * @return the new buffer, or null when a tab of the file has unsaved changes
   */
  Buffer addRecovered(Recovered r, Session.Tab tab) {
    int index = documents.size();
    OpenDocument open = r.real() == null ? null : find(r.real());
    if (disposing || (open != null && open.document.isDirty())) {
      return null;
    }
    if (open != null) {
      index = documents.indexOf(open);
      discard(open);
    }
    Document document = r.document();
    OpenDocument d =
        add(
            document,
            r.real(),
            r.base() == null ? null : r.base().stamp(),
            r.reserved(),
            r.layout(),
            index,
            false);
    RecoveryFile.Header header = r.header();
    if (r.real() == null) {
      d.untitled = header.title().isEmpty() ? d.untitled : header.title();
    } else {
      files.watch(d, r.given(), r.base());
      RecoveredBaseline.read(d, r.real(), workers, ui, budget, options);
    }
    document.setLineEnding(lineEnding(header.lineEnding()));
    document.markUnsaved();
    d.updateTitle();
    if (tab != null) {
      applyTab(d, tab);
      d.file.forced = tab.encoding().isEmpty() ? null : document.encoding().charset();
    } else {
      restoreGrammar(d, header.language());
      SelectionSet set = SessionText.selections(header.selection(), document.text().length());
      d.editor.restoreView(set, 0, new int[0]);
      d.tab.showCaret();
    }
    view.documentStateChanged();
    for (Buffer buffer : dirtyBuffers()) {
      if (buffer.id().equals(d.bufferId)) {
        return buffer;
      }
    }
    return null;
  }

  private static LineEnding lineEnding(String name) {
    try {
      return LineEnding.valueOf(name);
    } catch (IllegalArgumentException e) {
      return LineEnding.LF;
    }
  }
}
