package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.CaretNavigation;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.FoldRegionSource;
import io.github.ajdailey.qcaret.app.editor.IndentFoldSource;
import io.github.ajdailey.qcaret.app.editor.IndentRules;
import io.github.ajdailey.qcaret.app.editor.PatternIndentRules;
import io.github.ajdailey.qcaret.app.editor.SmartIndent;
import io.github.ajdailey.qcaret.app.editor.TokenBracketFilter;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.AutoClose;
import io.github.ajdailey.qcaret.core.edit.BlockEdits;
import io.github.ajdailey.qcaret.core.edit.Brackets;
import io.github.ajdailey.qcaret.core.edit.CommentEdits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineEdits;
import io.github.ajdailey.qcaret.core.edit.LineTransforms;
import io.github.ajdailey.qcaret.core.edit.Moves;
import io.github.ajdailey.qcaret.core.edit.Occurrences;
import io.github.ajdailey.qcaret.core.edit.Pairs;
import io.github.ajdailey.qcaret.core.edit.Words;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Runs one editor view's input against its document: every edit is one {@link Document#apply} with
 * the view's {@link SelectionModel} as origin, and undo and redo restore that model. Typing,
 * Backspace and Delete apply at every selection and coalesce into undo steps by kind; cut, paste,
 * line commands and edits the widget made on its own are commands. A read-only document ignores
 * edits. After each edit or caret move the view scrolls to the primary caret.
 *
 * <ul>
 *   <li>Caret moves apply to every selection; vertical moves follow the projection's rows ({@link
 *       CaretNavigation}) and Page Up and Down move by the view's {@link ViewLayout#pageRows}.
 *   <li>Block selection: Alt+Shift with an arrow key, a mouse drag in block mode, or Shift with an
 *       arrow key in column selection mode moves the block's caret corner; columns past a line's
 *       end are virtual. In a block, typing, Backspace, Delete, cut and paste follow {@link
 *       BlockEdits} and the block stays a block.
 *   <li>Paste gives one clipboard line to each selection when the counts match.
 *   <li>Add Next Occurrence searches a snapshot on a {@code qcaret-cpu-} worker; a result for an
 *       older revision is dropped.
 *   <li>Input method composition text applies outside undo ({@link Document#preedit}); any edit
 *       first removes it.
 *   <li>Toggle Line Comment and Toggle Block Comment use the document grammar's delimiters ({@link
 *       CommentEdits}), at every selection, as one undo step.
 *   <li>Enter and typed closers indent through {@link SmartIndent} and the view's {@link
 *       IndentRules}.
 *   <li>Folding: the fold commands act on the primary caret's line. Folding moves every caret out
 *       of the hidden lines, to the end of the region's first line; a primary caret placed in
 *       hidden lines by any other command, such as find or go to line, expands the regions around
 *       it. Left, Right, word and document-end moves step over hidden lines.
 *   <li>Typing and Backspace follow {@link AutoClose} with {@link #pairs()} unless {@code
 *       editor.autoClose} is off.
 *   <li>Sort, Remove Duplicate Lines and Trim Trailing Whitespace compute their edit on a {@code
 *       qcaret-cpu-} worker and apply it on the UI thread, with the view busy from the command to
 *       the apply; a result for an older revision is dropped with a notice.
 *   <li>The view shows the bracket beside the primary caret with its partner, searched on the UI
 *       thread within the visible range and {@value #BRACKET_SCAN} chars on each side, and on a
 *       worker beyond that; and the whole-word occurrences of the word at the primary caret in the
 *       visible range, found on a worker. Both are recomputed one UI turn after a caret move, an
 *       edit or a scroll, and a result for an older revision is dropped.
 *   <li>Word completion ({@link WordCompletion}) shows its list in the view; while it shows, Up,
 *       Down, Page Up and Page Down move in the list, Enter and Tab insert the selected word at
 *       every caret, Esc closes it, and typing narrows it.
 * </ul>
 *
 * <p>The controller also schedules the projection's rewraps: each runs on a {@code qcaret-cpu-}
 * worker, and a newer request cancels the running one. Fold regions come from a {@link
 * FoldRegionSource} on a {@code qcaret-cpu-} worker, {@value #FOLD_DELAY_MILLIS} ms after the last
 * change, one run at a time; a result for an older revision is dropped and the regions requested
 * again. Confined to the UI thread.
 */
public final class EditorController implements EditorInput {
  /** Rows a page move takes without a view. */
  static final int DEFAULT_PAGE_ROWS = 20;

  /** Milliseconds from a change to the fold region update it starts. */
  static final int FOLD_DELAY_MILLIS = 300;

  private final Document document;
  private final SelectionModel selections;
  private final DisplayProjection projection;
  private ClipboardService clipboard;
  private final Workers workers;
  private final UiDispatcher ui;
  private Runnable cancelSave;
  private EditorView view;
  private CancellationToken rewrap;
  private CancellationToken occurrence;
  private Runnable escapeListener;
  private boolean columnMode;
  private String highSurrogate;
  private boolean disposed;
  private Grammar.Comments comments = Grammar.Comments.NONE;
  private FoldRegionSource foldSource;
  private IndentRules indentRules = IndentRules.BRACKETS;
  private boolean foldsRunning;
  private DisplayProjection.FoldAll foldAll;
  private boolean foldsAgain;
  private final Runnable foldTimer =
      new Runnable() {
        @Override
        public void run() {
          scheduleFolds();
        }
      };
  private final SelectionListener revealer =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          revealPrimary();
        }
      };
  private final DocumentListener foldFollower =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          restoredFolds = null;
          restoredTop = -1;
          wrapTop = -1;
          ui.cancelTimer(foldTimer);
          ui.timerExec(FOLD_DELAY_MILLIS, foldTimer);
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Fold regions do not depend on the dirty state.
        }
      };

  /**
   * Creates the controller.
   *
   * @param projection the view's display text, over the document
   * @param selections the view's selections
   * @param clipboard the system clipboard
   * @param workers runs rewraps
   * @param ui receives rewrap results
   * @param cancelSave cancels a running save of the document and a running comparison, for {@link
   *     #cancel}
   */
  public EditorController(
      DisplayProjection projection,
      SelectionModel selections,
      ClipboardService clipboard,
      Workers workers,
      UiDispatcher ui,
      Runnable cancelSave) {
    this.document = projection.document();
    this.projection = projection;
    this.selections = selections;
    this.clipboard = clipboard;
    this.workers = workers;
    this.ui = ui;
    this.cancelSave = cancelSave;
    projection.setRewrapHook(
        new Runnable() {
          @Override
          public void run() {
            scheduleRewrap();
          }
        });
    selections.addListener(revealer);
    document.addListener(foldFollower);
    scheduleFolds();
    selections.addListener(selectionListener);
    document.addListener(documentListener);
  }

  /**
   * Connects the view the controller scrolls.
   *
   * @param editorView the view
   */
  public void attach(EditorView editorView) {
    view = editorView;
    if (bookmarks != null) {
      view.setBookmarks(bookmarks);
    }
    view.setMarks(marksWhitespace, marksLineEndings);
    view.setOverviewShown(overviewShown);
    if (columnMode) {
      view.setColumnMode(true);
    }
  }

  /** Lets go of the view before its tab moves to another window; closes the completion list. */
  void releaseView() {
    if (completionShown != null && completion != null) {
      completion.cancel();
    }
    completionShown = null;
    view = null;
  }

  /**
   * Takes the routes of the window the tab moved to.
   *
   * @param windowClipboard that window's clipboard
   * @param windowCancelSave cancels a running save there
   */
  void rebind(ClipboardService windowClipboard, Runnable windowCancelSave) {
    clipboard = windowClipboard;
    cancelSave = windowCancelSave;
  }

  /**
   * Returns the document.
   *
   * @return the document edited
   */
  public Document document() {
    return document;
  }

  /**
   * Returns the view's selections.
   *
   * @return the selection model
   */
  public SelectionModel selections() {
    return selections;
  }

  /**
   * Returns the projection.
   *
   * @return the view's display text
   */
  public DisplayProjection projection() {
    return projection;
  }

  /** Cancels a running rewrap and stops following the document. */
  public void dispose() {
    if (!disposed) {
      disposed = true;
      if (rewrap != null) {
        rewrap.cancel();
      }
      if (occurrence != null) {
        occurrence.cancel();
      }
      ui.cancelTimer(foldTimer);
      selections.removeListener(revealer);
      document.removeListener(foldFollower);
      disposeProductivity();
      if (overview != null) {
        overview.dispose();
      }
      projection.dispose();
      selections.dispose();
    }
  }

  // Word wrap.

  /**
   * Turns word wrap on or off; the rows follow when the rewrap finishes.
   *
   * @param on true to wrap at the view's width
   */
  public void setWrap(boolean on) {
    projection.setWrap(on);
    scheduleRewrap();
  }

  @Override
  public void viewColumnsChanged(int columns) {
    projection.setViewColumns(columns);
    scheduleRewrap();
  }

  /** Applies the document's tab width to the rows and the view. */
  void tabWidthChanged() {
    projection.setTabWidth(document.tabWidth());
    scheduleRewrap();
    if (view != null) {
      view.setTabWidth(document.tabWidth());
    }
    if (foldSource == null) {
      ui.cancelTimer(foldTimer);
      ui.timerExec(0, foldTimer);
    }
  }

  // Viewport, for find highlights.

  private int visibleStart;
  private int visibleEnd;
  private Runnable viewportListener;

  @Override
  public void viewportChanged(int start, int end) {
    restoredTop = -1;
    if (rewrap == null) {
      wrapTop = -1;
    }
    int from = projection.toModel(start);
    int to = projection.toModel(end);
    if (from != visibleStart || to != visibleEnd) {
      visibleStart = from;
      visibleEnd = to;
      if (viewportListener != null) {
        viewportListener.run();
      }
      scheduleHighlights();
    }
  }

  /**
   * Returns the model offset of the first visible row.
   *
   * @return the offset, 0 before the view reported
   */
  public int visibleStart() {
    return visibleStart;
  }

  /**
   * Returns the model offset after the last visible row.
   *
   * @return the offset, 0 before the view reported
   */
  public int visibleEnd() {
    return visibleEnd;
  }

  // Session state.

  /** The line {@link #restoreView} scrolled to, until the view reports its viewport. */
  private int restoredTop = -1;

  /** The line a restored view scrolls back to after each rewrap, or -1. */
  private int wrapTop = -1;

  /**
   * The lines {@link #restoreView} collapses as regions arrive, until an edit or a fold command.
   */
  private int[] restoredFolds;

  private int foldsTop;

  /**
   * Puts back a view's remembered state. The caret and top line apply now; each fold applies once a
   * region starts on its line, and the view scrolls to the top line again after it.
   *
   * @param set the selections, or null to keep them
   * @param top the zero-based first visible line
   * @param folds the first lines of the regions to collapse
   */
  void restoreView(SelectionSet set, int top, int[] folds) {
    if (set != null) {
      selections.set(set);
    }
    int line = Math.max(0, Math.min(top, document.text().lineCount() - 1));
    restoredFolds = folds.length == 0 ? null : folds;
    foldsTop = line;
    applyRestoredFolds();
    restoredTop = line;
    wrapTop = line;
    if (view != null) {
      view.scrollToRow(projection.rowOfLine(line));
    }
  }

  private void applyRestoredFolds() {
    if (restoredFolds == null) {
      return;
    }
    boolean folded = false;
    for (int line : restoredFolds) {
      folded |= !projection.collapsed(line) && projection.foldAt(line);
    }
    if (folded) {
      caretsOutOfFolds();
      if (view != null) {
        view.scrollToRow(projection.rowOfLine(foldsTop));
      }
    }
  }

  /**
   * Returns the first visible line.
   *
   * @return the zero-based line, the restored one until the view reports
   */
  int topLine() {
    return restoredTop >= 0 ? restoredTop : document.text().lineAt(visibleStart);
  }

  /**
   * Sets what runs when the visible range changes.
   *
   * @param listener the listener, or null
   */
  public void setViewportListener(Runnable listener) {
    viewportListener = listener;
  }

  /**
   * Returns the view.
   *
   * @return the view, or null before {@link #attach}
   */
  public EditorView view() {
    return view;
  }

  /** Starts the rewrap the projection needs, canceling the running one. */
  void scheduleRewrap() {
    if (disposed) {
      return;
    }
    final DisplayProjection.Rewrap request = projection.rewrapRequest();
    if (request == null) {
      return;
    }
    if (rewrap != null) {
      rewrap.cancel();
    }
    final CancellationToken token = new CancellationToken();
    rewrap = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final DisplayProjection.Layout rows;
              try {
                rows = DisplayProjection.compute(request, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (!disposed) {
                        projection.install(request, rows);
                        if (rewrap == token) {
                          rewrap = null;
                        }
                        scheduleRewrap();
                        if (wrapTop >= 0 && view != null) {
                          view.scrollToRow(projection.rowOfLine(wrapTop));
                        }
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      rewrap = null;
    }
  }

  // Commands.

  @Override
  public boolean execute(CommandId command) {
    if (!document.isReadOnly()) {
      document.clearPreedit();
    }
    if (completionShown != null && completionKey(command)) {
      return true;
    }
    switch (command) {
      case UNDO -> undo();
      case REDO -> redo();
      case CUT -> cut();
      case COPY -> copy();
      case PASTE -> paste();
      case SELECT_ALL -> selectAll();
      case NEWLINE -> newline();
      case DELETE_BACK -> backspace();
      case DELETE_FORWARD -> deleteForward();
      case CANCEL -> cancel();
      default -> {
        return executeEditing(command);
      }
    }
    return true;
  }

  /** Runs a caret move, selection or line command; returns false for another command. */
  private boolean executeEditing(CommandId command) {
    switch (command) {
      case CARET_LEFT, SELECT_LEFT -> move(Motion.LEFT, command == CommandId.SELECT_LEFT);
      case CARET_RIGHT, SELECT_RIGHT -> move(Motion.RIGHT, command == CommandId.SELECT_RIGHT);
      case CARET_UP, SELECT_UP -> move(Motion.UP, command == CommandId.SELECT_UP);
      case CARET_DOWN, SELECT_DOWN -> move(Motion.DOWN, command == CommandId.SELECT_DOWN);
      case WORD_LEFT, SELECT_WORD_LEFT ->
          move(Motion.WORD_LEFT, command == CommandId.SELECT_WORD_LEFT);
      case WORD_RIGHT, SELECT_WORD_RIGHT ->
          move(Motion.WORD_RIGHT, command == CommandId.SELECT_WORD_RIGHT);
      case LINE_START, SELECT_LINE_START ->
          move(Motion.LINE_START, command == CommandId.SELECT_LINE_START);
      case LINE_END, SELECT_LINE_END -> move(Motion.LINE_END, command == CommandId.SELECT_LINE_END);
      case PAGE_UP, SELECT_PAGE_UP -> move(Motion.PAGE_UP, command == CommandId.SELECT_PAGE_UP);
      case PAGE_DOWN, SELECT_PAGE_DOWN ->
          move(Motion.PAGE_DOWN, command == CommandId.SELECT_PAGE_DOWN);
      case DOCUMENT_START, SELECT_DOCUMENT_START ->
          move(Motion.DOCUMENT_START, command == CommandId.SELECT_DOCUMENT_START);
      case DOCUMENT_END, SELECT_DOCUMENT_END ->
          move(Motion.DOCUMENT_END, command == CommandId.SELECT_DOCUMENT_END);
      case BLOCK_LEFT -> moveBlock(0, -1);
      case BLOCK_RIGHT -> moveBlock(0, 1);
      case BLOCK_UP -> moveBlock(-1, 0);
      case BLOCK_DOWN -> moveBlock(1, 0);
      case INSERT_TAB -> insertTab();
      case INDENT ->
          applyEdit(
              LineEdits.indent(
                  document.text(), current(), document.tabWidth(), document.insertSpaces()));
      case OUTDENT -> applyEdit(LineEdits.outdent(document.text(), current(), document.tabWidth()));
      case DUPLICATE ->
          applyEdit(LineEdits.duplicate(document.text(), current(), document.lineEnding().text()));
      case DELETE_LINE -> applyEdit(LineEdits.deleteLines(document.text(), current()));
      case MOVE_LINES_UP -> applyEdit(LineEdits.moveLines(document.text(), current(), true));
      case MOVE_LINES_DOWN -> applyEdit(LineEdits.moveLines(document.text(), current(), false));
      case JOIN_LINES -> applyEdit(LineEdits.joinLines(document.text(), current()));
      case UPPER_CASE -> applyEdit(LineEdits.changeCase(document.text(), current(), true));
      case LOWER_CASE -> applyEdit(LineEdits.changeCase(document.text(), current(), false));
      case ADD_CARET_ABOVE -> addCaret(-1);
      case ADD_CARET_BELOW -> addCaret(1);
      case ADD_NEXT_OCCURRENCE -> addNextOccurrence();
      case SPLIT_INTO_LINES -> {
        selections.set(LineEdits.splitIntoLines(document.text(), current()));
        showCaret();
      }
      case COLUMN_MODE -> setColumnMode(!columnMode);
      case TOGGLE_LINE_COMMENT ->
          applyEdit(
              CommentEdits.toggleLines(
                  document.text(),
                  current(),
                  comments.line(),
                  comments.blockStart(),
                  comments.blockEnd()));
      case TOGGLE_BLOCK_COMMENT ->
          applyEdit(
              CommentEdits.toggleBlock(
                  document.text(), current(), comments.blockStart(), comments.blockEnd()));
      case FOLD, UNFOLD, TOGGLE_FOLD, FOLD_ALL, UNFOLD_ALL -> fold(command);
      case SORT_LINES_ASCENDING,
          SORT_LINES_DESCENDING,
          SORT_LINES_IGNORE_CASE,
          REMOVE_DUPLICATE_LINES,
          TRIM_TRAILING_WHITESPACE ->
          transformLines(command);
      case COMPLETE_WORD -> requestCompletion(true);
      case GO_TO_MATCHING_BRACKET -> matchingBracket(false);
      case SELECT_TO_MATCHING_BRACKET -> matchingBracket(true);
      case TOGGLE_BOOKMARK -> toggleBookmarks();
      case NEXT_BOOKMARK -> nextBookmark(true);
      case PREVIOUS_BOOKMARK -> nextBookmark(false);
      case CLEAR_BOOKMARKS -> {
        if (bookmarks != null) {
          bookmarks.clear();
        }
      }
      case EDIT_BOOKMARK_COMMENT -> {
        if (commentRequest != null) {
          commentRequest.accept(document.text().lineAt(current().primary().caret()));
        }
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * Sets the comment delimiters the comment commands use.
   *
   * @param value the document grammar's delimiters
   */
  void setComments(Grammar.Comments value) {
    comments = value;
  }

  /**
   * Takes the document grammar's comments, fold mode, indent rules and pairs. A {@code brackets} or
   * {@code markers} grammar's fold regions come from the document's highlighter; every other
   * grammar, and plain text, folds by indentation.
   *
   * @param grammar the grammar, or null for plain text
   */
  void setGrammar(Grammar grammar) {
    comments = grammar == null ? Grammar.Comments.NONE : grammar.comments();
    Grammar.FoldMode mode = grammar == null ? Grammar.FoldMode.NONE : grammar.folding().mode();
    boolean regions = mode == Grammar.FoldMode.BRACKETS || mode == Grammar.FoldMode.MARKERS;
    setFoldSource(regions ? syntax : null);
    indentRules =
        grammar == null
                || (grammar.indentIncrease() == null
                    && grammar.indentDecrease() == null
                    && grammar.indentDedentNext() == null)
            ? IndentRules.BRACKETS
            : new PatternIndentRules(
                grammar.indentIncrease(), grammar.indentDecrease(), grammar.indentDedentNext());
    pairs = grammar == null ? Pairs.DEFAULT : pairsOf(grammar.autoClose());
    bracketPairs = grammar == null ? Pairs.DEFAULT : pairsOf(grammar.brackets());
  }

  /** Returns the single-char pairs of a grammar list; {@link Pairs#DEFAULT} for an empty list. */
  private static Pairs pairsOf(List<String[]> list) {
    StringBuilder chars = new StringBuilder();
    for (String[] pair : list) {
      if (pair[0].length() == 1 && pair[1].length() == 1) {
        chars.append(pair[0]).append(pair[1]);
      }
    }
    return list.isEmpty() ? Pairs.DEFAULT : new Pairs(chars.toString());
  }

  /**
   * Sets the document's highlighter, whose tokens leave strings and comments out of bracket
   * matching and whose regions a {@code brackets} or {@code markers} grammar folds by.
   *
   * @param highlighter the highlighter
   */
  void setSyntax(SyntaxHighlighter highlighter) {
    syntax = highlighter;
  }

  /** Runs the fold step again when the regions come from the highlighter, which has new ones. */
  void syntaxRegionsChanged() {
    if (foldSource != null && foldSource == syntax) {
      scheduleFolds();
    }
  }

  /** Returns the comment delimiters; for {@link CommandStates}. */
  Grammar.Comments comments() {
    return comments;
  }

  /**
   * Reports whether column selection mode is on.
   *
   * @return true when a drag and Shift with an arrow key select a block
   */
  boolean columnMode() {
    return columnMode;
  }

  private void setColumnMode(boolean on) {
    columnMode = on;
    if (view != null) {
      view.setColumnMode(on);
    }
  }

  /**
   * Moves the primary caret to a line and column and drops the other selections.
   *
   * @param line the zero-based line, clamped to the text
   * @param column the zero-based visual column, clamped to the line
   */
  public void goTo(int line, int column) {
    jumping();
    TextSnapshot text = document.text();
    int at = Math.max(0, Math.min(line, text.lineCount() - 1));
    LineView lineView = text.lineView(at, new LineView());
    int index = Columns.index(lineView, Math.max(0, column), document.tabWidth(), false);
    selections.set(SelectionSet.caret(lineView.start() + index));
    showCaret();
  }

  // Navigation history.

  private Runnable jumpListener;

  /** Sets what runs before the caret jumps: Go to Line, a bookmark, a search result. */
  void setJumpListener(Runnable listener) {
    jumpListener = listener;
  }

  /** Reports a jump about to move the caret, so the history records where it was. */
  void jumping() {
    if (jumpListener != null) {
      jumpListener.run();
    }
  }

  /** Moves the primary caret to an offset, drops the other selections and shows the caret. */
  void moveCaret(int offset) {
    selections.set(SelectionSet.caret(Math.max(0, Math.min(offset, document.text().length()))));
    showCaret();
  }

  private SelectionSet current() {
    return selections.selections();
  }

  // Edits.

  @Override
  public void type(String typed) {
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    String text = wholeCodePoints(typed);
    if (text == null) {
      return;
    }
    BlockSelection block = selections.block();
    if (block != null && text.indexOf('\r') < 0 && text.indexOf('\n') < 0) {
      applyBlock(
          BlockEdits.type(
              document.text(), block, document.tabWidth(), text, EditBatch.Kind.TYPING));
      return;
    }
    Edit paired =
        autoClose && block == null && text.length() == 1
            ? AutoClose.type(document.text(), current(), text.charAt(0), pairs())
            : null;
    Edit outdent =
        block == null && paired == null
            ? SmartIndent.typed(
                document.text(),
                current(),
                text,
                indentRules,
                document.tabWidth(),
                document.insertSpaces())
            : null;
    if (paired != null) {
      applyTyped(paired);
    } else if (outdent != null) {
      applyEdit(outdent);
    } else {
      replaceSelections(text, EditBatch.Kind.TYPING);
    }
    typed(text);
  }

  /**
   * Returns typed text with a held high surrogate before its low one, or null while holding one.
   * The view sends one char per key event, and a block's column moves between two lone halves.
   */
  private String wholeCodePoints(String typed) {
    if (typed.length() == 1 && Character.isHighSurrogate(typed.charAt(0))) {
      highSurrogate = typed;
      return null;
    }
    String high = highSurrogate;
    highSurrogate = null;
    boolean paired = high != null && !typed.isEmpty() && Character.isLowSurrogate(typed.charAt(0));
    return paired ? high + typed : typed;
  }

  @Override
  public void newline() {
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    if (selections.block() != null) {
      replaceSelections(document.lineEnding().text(), EditBatch.Kind.TYPING);
      return;
    }
    applyEdit(
        SmartIndent.newline(
            document.text(),
            current(),
            document.lineEnding().text(),
            indentRules,
            document.tabWidth(),
            document.insertSpaces()));
  }

  @Override
  public void backspace() {
    BlockSelection block = selections.block();
    if (block != null) {
      if (!document.isReadOnly()) {
        applyBlock(BlockEdits.backspace(document.text(), block, document.tabWidth()));
      }
      return;
    }
    if (!document.isReadOnly()) {
      document.clearPreedit();
      Edit paired = autoClose ? AutoClose.backspace(document.text(), current(), pairs()) : null;
      if (paired != null) {
        applyTyped(paired);
        return;
      }
    }
    deleteAround(true);
  }

  @Override
  public void deleteForward() {
    BlockSelection block = selections.block();
    if (block != null) {
      if (!document.isReadOnly()) {
        applyBlock(BlockEdits.delete(document.text(), block, document.tabWidth()));
      }
      return;
    }
    deleteAround(false);
  }

  @Override
  public void cut() {
    if (!copySelections() || document.isReadOnly()) {
      return;
    }
    BlockSelection block = selections.block();
    if (block != null) {
      applyBlock(BlockEdits.delete(document.text(), block, document.tabWidth()));
    } else {
      replaceSelections("", EditBatch.Kind.COMMAND);
    }
  }

  @Override
  public void copy() {
    copySelections();
  }

  /**
   * Reports whether a selection holds text for Copy and Cut. Walks the carets, never the text, so a
   * block wider than a short line counts.
   *
   * @return true when a selection is not empty
   */
  public boolean hasSelection() {
    BlockSelection block = selections.block();
    if (block != null) {
      return block.anchorColumn() != block.caretColumn();
    }
    for (Selection s : selections.selections().selections()) {
      if (!s.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void paste() {
    String text = clipboard.clipboardText();
    if (text == null || text.isEmpty() || document.isReadOnly()) {
      return;
    }
    String ending = document.lineEnding().text();
    String converted = convertBreaks(text, ending);
    SelectionSet set = selections.selections();
    List<String> parts = splitLines(converted, ending);
    BlockSelection block = selections.block();
    if (block != null && parts.size() == set.size()) {
      applyEdit(BlockEdits.paste(document.text(), block, document.tabWidth(), parts));
    } else if (block != null && parts.size() == 1) {
      applyBlock(
          BlockEdits.type(
              document.text(), block, document.tabWidth(), converted, EditBatch.Kind.COMMAND));
    } else if (set.size() > 1 && parts.size() == set.size()) {
      apply(parts, EditBatch.Kind.COMMAND);
    } else {
      replaceSelections(converted, EditBatch.Kind.COMMAND);
    }
  }

  @Override
  public void undo() {
    if (!document.isReadOnly() && document.undo(selections)) {
      showCaret();
    }
  }

  @Override
  public void redo() {
    if (!document.isReadOnly() && document.redo(selections)) {
      showCaret();
    }
  }

  @Override
  public void selectAll() {
    selections.set(SelectionSet.of(List.of(Selection.of(0, document.text().length())), 0));
  }

  @Override
  public void cancel() {
    cancelSave.run();
    if (lineWork != null) {
      lineWork.cancel();
    }
    SelectionSet set = selections.selections();
    if (set.size() > 1 || selections.block() != null) {
      selections.set(SelectionSet.of(List.of(set.primary()), 0));
    } else if (escapeListener != null) {
      escapeListener.run();
    }
  }

  /**
   * Sets what {@link #cancel} runs when there are no secondary selections and no block.
   *
   * @param listener the listener, or null
   */
  void setEscapeListener(Runnable listener) {
    escapeListener = listener;
  }

  @Override
  public void replaceFromView(int start, int end, String text) {
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    int from = projection.toModel(start);
    int to = Math.max(from, projection.toModel(end));
    SelectionSet after = SelectionSet.caret(from + text.length());
    document.apply(EditBatch.replace(from, to - from, text), selections, after);
    showCaret();
  }

  @Override
  public void selectFromView(int anchor, int caret) {
    Selection selection = Selection.of(projection.toModel(anchor), projection.toModel(caret));
    SelectionSet set = SelectionSet.of(List.of(selection), 0);
    if (!set.equals(selections.selections()) || selections.block() != null) {
      selections.set(set);
    }
  }

  @Override
  public void menuFromView(int offset) {
    int at = projection.toModel(offset);
    for (Selection s : selections.selections().selections()) {
      if (!s.isEmpty() && s.start() <= at && at <= s.end()) {
        return;
      }
    }
    selectFromView(offset, offset);
  }

  @Override
  public void selectionsFromView(int[] ranges, int primary) {
    List<Selection> list = new ArrayList<Selection>(ranges.length / 2);
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      list.add(Selection.of(projection.toModel(ranges[i]), projection.toModel(ranges[i + 1])));
    }
    SelectionSet set = SelectionSet.of(list, primary);
    if (!set.equals(selections.selections())) {
      selections.set(set);
    }
  }

  // Block selection and carets from the mouse.

  @Override
  public void blockFromView(int anchorX, int anchorY, int x, int y) {
    ViewLayout layout = layout();
    if (layout == null) {
      return;
    }
    int anchorRow = layout.rowAt(anchorY);
    int row = layout.rowAt(y);
    setBlock(
        new BlockSelection(
            projection.lineOfRow(anchorRow),
            layout.columnAt(anchorRow, anchorX),
            projection.lineOfRow(row),
            layout.columnAt(row, x)));
  }

  @Override
  public void extendBlockFromView(int x, int y) {
    ViewLayout layout = layout();
    if (layout == null) {
      return;
    }
    BlockSelection block = selections.block();
    int[] anchor;
    if (block != null) {
      anchor = new int[] {block.anchorLine(), block.anchorColumn()};
    } else {
      int caret = selections.selections().primary().caret();
      TextSnapshot text = document.text();
      anchor =
          new int[] {text.lineAt(caret), CaretNavigation.column(text, caret, document.tabWidth())};
    }
    int row = layout.rowAt(y);
    setBlock(
        new BlockSelection(
            anchor[0], anchor[1], projection.lineOfRow(row), layout.columnAt(row, x)));
  }

  @Override
  public void toggleCaretFromView(int offset) {
    int at = projection.toModel(offset);
    SelectionSet set = selections.selections();
    beforeToggle = set;
    List<Selection> list = new ArrayList<Selection>(set.selections());
    for (int i = 0; i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.isEmpty() && s.caret() == at && list.size() > 1) {
        list.remove(i);
        int primary = set.primaryIndex();
        selections.set(
            SelectionSet.of(list, primary > i ? primary - 1 : Math.min(primary, list.size() - 1)));
        return;
      }
    }
    list.add(Selection.caret(at));
    selections.set(SelectionSet.of(list, list.size() - 1));
  }

  @Override
  public void addSelectionFromView(int anchor, int caret) {
    if (beforeToggle == null) {
      return;
    }
    List<Selection> list = new ArrayList<Selection>(beforeToggle.selections());
    list.add(Selection.of(projection.toModel(anchor), projection.toModel(caret)));
    selections.set(SelectionSet.of(list, list.size() - 1));
    showCaret();
  }

  // Input method composition.

  @Override
  public void preeditFromView(String text) {
    if (!document.isReadOnly()) {
      document.preedit(selections.selections().primary().start(), text);
    }
  }

  // Caret moves.

  /** A caret move. */
  private enum Motion {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    WORD_LEFT,
    WORD_RIGHT,
    LINE_START,
    LINE_END,
    PAGE_UP,
    PAGE_DOWN,
    DOCUMENT_START,
    DOCUMENT_END
  }

  /** Moves every caret; with {@code extend} the anchors stay, else each selection collapses. */
  private void move(Motion motion, boolean extend) {
    if (extend && columnMode) {
      switch (motion) {
        case LEFT -> moveBlock(0, -1);
        case RIGHT -> moveBlock(0, 1);
        case UP -> moveBlock(-1, 0);
        case DOWN -> moveBlock(1, 0);
        default -> moveAll(motion, true);
      }
      return;
    }
    moveAll(motion, extend);
  }

  private void moveAll(Motion motion, boolean extend) {
    SelectionSet set = selections.selections();
    TextSnapshot text = document.text();
    List<Selection> out = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      if (!extend && !s.isEmpty() && (motion == Motion.LEFT || motion == Motion.RIGHT)) {
        out.add(Selection.caret(motion == Motion.LEFT ? s.start() : s.end()));
        continue;
      }
      int[] target = target(text, motion, s);
      if (target[1] < 0) {
        target[0] = projection.visibleOffset(target[0], target[0] > s.caret());
      }
      out.add(
          extend
              ? new Selection(s.anchor(), target[0], target[1])
              : new Selection(target[0], target[0], target[1]));
    }
    selections.set(SelectionSet.of(out, set.primaryIndex()));
    showCaret();
  }

  /** Returns a caret's new offset and preferred column, -1 for a horizontal move. */
  private int[] target(TextSnapshot text, Motion motion, Selection s) {
    int caret = s.caret();
    int tab = document.tabWidth();
    return switch (motion) {
      case LEFT -> new int[] {Moves.left(text, caret), -1};
      case RIGHT -> new int[] {Moves.right(text, caret), -1};
      case WORD_LEFT -> new int[] {Words.left(text, caret), -1};
      case WORD_RIGHT -> new int[] {Words.right(text, caret), -1};
      case LINE_START -> new int[] {Moves.home(text, caret), -1};
      case LINE_END -> new int[] {Moves.end(text, caret), -1};
      case DOCUMENT_START -> new int[] {0, -1};
      case DOCUMENT_END -> new int[] {text.length(), -1};
      case UP -> CaretNavigation.vertical(projection, caret, s.preferredColumn(), -1, tab);
      case DOWN -> CaretNavigation.vertical(projection, caret, s.preferredColumn(), 1, tab);
      case PAGE_UP ->
          CaretNavigation.vertical(projection, caret, s.preferredColumn(), -pageRows(), tab);
      case PAGE_DOWN ->
          CaretNavigation.vertical(projection, caret, s.preferredColumn(), pageRows(), tab);
    };
  }

  private int pageRows() {
    ViewLayout layout = layout();
    return layout == null ? DEFAULT_PAGE_ROWS : Math.max(1, layout.pageRows());
  }

  private ViewLayout layout() {
    return view == null ? null : view.layout();
  }

  /**
   * Moves the block's caret corner, starting a block at the primary selection when none is held.
   */
  private void moveBlock(int lines, int columns) {
    TextSnapshot text = document.text();
    int tab = document.tabWidth();
    BlockSelection block = selections.block();
    if (block == null) {
      Selection primary = selections.selections().primary();
      block =
          new BlockSelection(
              text.lineAt(primary.anchor()),
              CaretNavigation.column(text, primary.anchor(), tab),
              text.lineAt(primary.caret()),
              CaretNavigation.column(text, primary.caret(), tab));
    }
    int line = Math.max(0, Math.min(text.lineCount() - 1, block.caretLine() + lines));
    int column = block.caretColumn();
    if (columns != 0) {
      column = CaretNavigation.step(text, line, column, columns > 0, tab);
    }
    setBlock(new BlockSelection(block.anchorLine(), block.anchorColumn(), line, column));
  }

  private void setBlock(BlockSelection block) {
    selections.setBlock(block, document.tabWidth());
    showCaret();
  }

  // Folding and indent rules.

  /**
   * Sets where fold regions come from and requests them again.
   *
   * @param source the source, or null for indentation folding at the document's tab width
   */
  public void setFoldSource(FoldRegionSource source) {
    foldSource = source;
    ui.cancelTimer(foldTimer);
    ui.timerExec(0, foldTimer);
  }

  /**
   * Sets the rules smart indent follows.
   *
   * @param rules the rules
   */
  public void setIndentRules(IndentRules rules) {
    indentRules = rules;
  }

  @Override
  public void toggleFoldFromView(int row) {
    restoredFolds = null;
    int line = projection.lineOfRow(Math.max(0, Math.min(row, projection.rowCount() - 1)));
    if (!projection.unfold(line) && projection.foldAt(line)) {
      caretsOutOfFolds();
    }
  }

  private void fold(CommandId command) {
    restoredFolds = null;
    foldAll = null;
    if (command == CommandId.FOLD_ALL) {
      requestFoldAll();
      return;
    }
    int line = projection.lineAt(selections.selections().primary().caret());
    boolean changed =
        switch (command) {
          case FOLD -> projection.fold(line);
          case UNFOLD -> projection.unfold(line);
          case TOGGLE_FOLD -> projection.unfold(line) || projection.fold(line);
          default -> projection.unfoldAll();
        };
    if (changed) {
      caretsOutOfFolds();
      showCaret();
    }
  }

  /**
   * Measures Fold All's spans on a worker and collapses them at the same text and rows; asks again
   * when those changed meanwhile.
   */
  private void requestFoldAll() {
    final DisplayProjection.FoldAll request = projection.foldAllRequest();
    if (request == null || disposed) {
      return;
    }
    foldAll = request;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                DisplayProjection.computeFoldAll(request);
              } finally {
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        foldAllComputed(request);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      foldAll = null;
    }
  }

  private void foldAllComputed(DisplayProjection.FoldAll request) {
    if (disposed || request != foldAll) {
      return;
    }
    foldAll = null;
    switch (projection.installFoldAll(request)) {
      case APPLIED -> {
        caretsOutOfFolds();
        showCaret();
      }
      case STALE -> requestFoldAll();
      default -> {
        // A fold command since the request decides the collapsed regions.
      }
    }
  }

  /** Moves every caret and anchor in hidden lines to the end of the line before them. */
  private void caretsOutOfFolds() {
    if (selections.block() != null) {
      return;
    }
    SelectionSet set = selections.selections();
    List<Selection> out = new ArrayList<Selection>(set.size());
    boolean moved = false;
    for (Selection s : set.selections()) {
      int anchor = projection.visibleOffset(s.anchor(), false);
      int caret = projection.visibleOffset(s.caret(), false);
      moved |= anchor != s.anchor() || caret != s.caret();
      out.add(Selection.of(anchor, caret));
    }
    if (moved) {
      selections.set(SelectionSet.of(out, set.primaryIndex()));
    }
  }

  /** Expands the regions that hide the primary caret. */
  private void revealPrimary() {
    if (projection.collapsedCount() > 0) {
      projection.reveal(projection.lineAt(selections.selections().primary().caret()));
    }
  }

  /** Computes the fold regions on a worker, after the one running. */
  private void scheduleFolds() {
    if (disposed) {
      return;
    }
    if (foldsRunning) {
      foldsAgain = true;
      return;
    }
    final FoldRegionSource source =
        foldSource != null ? foldSource : new IndentFoldSource(document.tabWidth());
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    foldsRunning = true;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              List<int[]> regions = null;
              try {
                regions = source.regions(text, revision);
              } finally {
                final List<int[]> result = regions;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        foldsComputed(revision, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      foldsRunning = false;
    }
  }

  /** Installs computed regions while the document is at their revision; null after a failure. */
  private void foldsComputed(long revision, List<int[]> regions) {
    foldsRunning = false;
    if (disposed) {
      return;
    }
    if (foldsAgain) {
      foldsAgain = false;
      scheduleFolds();
      return;
    }
    if (regions != null && document.revision() == revision) {
      projection.setFoldRegions(regions);
      applyRestoredFolds();
    }
  }

  // Selections.

  /** Adds a caret one row above the top selection or below the bottom one; it becomes primary. */
  private void addCaret(int rows) {
    SelectionSet set = selections.selections();
    List<Selection> list = set.selections();
    Selection edge = rows < 0 ? list.get(0) : list.get(list.size() - 1);
    int[] target =
        CaretNavigation.vertical(
            projection, edge.caret(), edge.preferredColumn(), rows, document.tabWidth());
    if (target[0] == edge.caret()) {
      return;
    }
    List<Selection> out = new ArrayList<Selection>(list);
    out.add(new Selection(target[0], target[0], target[1]));
    selections.set(SelectionSet.of(out, out.size() - 1));
    showCaret();
  }

  /**
   * Selects the word at a primary caret; with a primary range, adds its next occurrence after it as
   * the new primary. The search runs on a worker.
   */
  private void addNextOccurrence() {
    final SelectionSet set = selections.selections();
    Selection primary = set.primary();
    final TextSnapshot text = document.text();
    if (primary.isEmpty()) {
      int[] word = Words.at(text, primary.caret());
      if (word.length > 0) {
        List<Selection> list = new ArrayList<Selection>(set.selections());
        list.set(set.primaryIndex(), Selection.of(word[0], word[1]));
        selections.set(SelectionSet.of(list, set.primaryIndex()));
      }
      return;
    }
    final String needle = text.subSequence(primary.start(), primary.end()).toString();
    final int from = primary.end();
    final long revision = document.revision();
    if (occurrence != null) {
      occurrence.cancel();
    }
    final CancellationToken token = new CancellationToken();
    occurrence = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final int found;
              try {
                found = Occurrences.next(text, needle, from, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      occurrenceFound(token, revision, found, needle.length());
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      occurrence = null;
    }
  }

  private void occurrenceFound(CancellationToken token, long revision, int found, int length) {
    if (token != occurrence) {
      return;
    }
    occurrence = null;
    if (disposed || found < 0 || document.revision() != revision) {
      return;
    }
    Selection next = Selection.of(found, found + length);
    List<Selection> list = new ArrayList<Selection>(selections.selections().selections());
    for (Selection s : list) {
      if (s.start() < next.end() && next.start() < s.end()) {
        return;
      }
    }
    list.add(next);
    selections.set(SelectionSet.of(list, list.size() - 1));
    showCaret();
  }

  // Applying edits.

  private void showCaret() {
    if (view != null) {
      view.showCaret();
    }
  }

  private void insertTab() {
    if (document.isReadOnly()) {
      return;
    }
    BlockSelection block = selections.block();
    if (block != null) {
      type(document.insertSpaces() ? " ".repeat(document.tabWidth()) : "\t");
      return;
    }
    applyEdit(
        LineEdits.insertTab(
            document.text(), current(), document.tabWidth(), document.insertSpaces()));
  }

  /** Applies an edit from {@code core.edit}; null or a read-only document does nothing. */
  private void applyEdit(Edit edit) {
    if (edit == null || document.isReadOnly() || edit.batch().replacements().isEmpty()) {
      return;
    }
    document.apply(edit.batch(), selections, edit.after());
    showCaret();
  }

  /** Applies a block edit; with nothing to replace, only the block moves. */
  private void applyBlock(BlockEdits.Result result) {
    if (result == null) {
      return;
    }
    if (result.batch().replacements().isEmpty()) {
      setBlock(result.after());
      return;
    }
    document.applyInBlock(result.batch(), selections, result.after());
    showCaret();
  }

  /**
   * Copies the selected text, one selection per line, or a block's rows, short rows empty; returns
   * false when nothing is selected.
   */
  private boolean copySelections() {
    TextSnapshot text = document.text();
    String ending = document.lineEnding().text();
    BlockSelection block = selections.block();
    if (block != null) {
      List<String> rows = BlockEdits.rowsText(text, block, document.tabWidth());
      String joined = String.join(ending, rows);
      if (joined.length() == (rows.size() - 1) * ending.length()) {
        return false;
      }
      clipboard.setText(joined);
      return true;
    }
    StringBuilder out = new StringBuilder();
    boolean any = false;
    for (Selection s : selections.selections().selections()) {
      if (s.isEmpty()) {
        continue;
      }
      if (any) {
        out.append(ending);
      }
      out.append(text, s.start(), s.end());
      any = true;
    }
    if (any) {
      clipboard.setText(out.toString());
    }
    return any;
  }

  /** Replaces every selection with the same text; each caret lands after its insert. */
  private void replaceSelections(String text, EditBatch.Kind kind) {
    int count = selections.selections().size();
    List<String> parts = new ArrayList<String>(count);
    for (int i = 0; i < count; i++) {
      parts.add(text);
    }
    apply(parts, kind);
  }

  /** Replaces selection i with {@code parts[i]}. */
  private void apply(List<String> parts, EditBatch.Kind kind) {
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    SelectionSet set = selections.selections();
    List<Replacement> replacements = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int i = 0;
    for (Selection s : set.selections()) {
      String part = parts.get(i++);
      replacements.add(new Replacement(s.start(), s.end() - s.start(), part));
      after.add(Selection.caret(s.start() + shift + part.length()));
      shift += part.length() - (s.end() - s.start());
    }
    EditBatch batch = EditBatch.of(kind, replacements);
    if (batch.replacements().isEmpty()) {
      return;
    }
    document.apply(batch, selections, SelectionSet.of(after, set.primaryIndex()));
    showCaret();
  }

  /**
   * Deletes each selection, or one char before ({@code back}) or after each caret: a CR LF pair and
   * a surrogate pair go as one.
   */
  private void deleteAround(boolean back) {
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    TextSnapshot text = document.text();
    SelectionSet set = selections.selections();
    List<Replacement> replacements = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    boolean anySelected = false;
    int shift = 0;
    int lastEnd = 0;
    for (Selection s : set.selections()) {
      int from = s.start();
      int to = s.end();
      if (s.isEmpty()) {
        if (back) {
          from = Moves.left(text, s.start());
        } else if (s.end() >= lastEnd) {
          to = Moves.right(text, s.end());
        }
      } else {
        anySelected = true;
      }
      from = Math.max(from, lastEnd);
      to = Math.max(to, from);
      if (to > from) {
        replacements.add(new Replacement(from, to - from, ""));
      }
      after.add(Selection.caret(from + shift));
      shift -= to - from;
      lastEnd = to;
    }
    if (replacements.isEmpty()) {
      return;
    }
    EditBatch.Kind kind =
        anySelected
            ? EditBatch.Kind.COMMAND
            : back ? EditBatch.Kind.BACKSPACE : EditBatch.Kind.DELETE;
    document.apply(
        EditBatch.of(kind, replacements), selections, SelectionSet.of(after, set.primaryIndex()));
    showCaret();
  }

  // Productivity: auto-close, line transforms, bookmarks, brackets, occurrences, completion.

  /** Chars scanned on each side of the visible range for a bracket's partner on the UI thread. */
  static final int BRACKET_SCAN = 1 << 14;

  /** Occurrences of the word at the caret highlighted in one view at most. */
  static final int VIEW_OCCURRENCES = 2000;

  /** Rows Page Up and Page Down move in the completion list. */
  static final int COMPLETION_PAGE = 8;

  /** Word chars before the caret a completion prefix takes at most. */
  static final int MAX_PREFIX = 100;

  private final SelectionListener selectionListener =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          if (completionShown != null) {
            refilterCompletion();
          }
          scheduleHighlights();
        }
      };

  private final DocumentListener documentListener =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          if (view != null && (occurrences.length > 0 || brackets.length > 0)) {
            occurrences = FindController.shifted(occurrences, change.edits());
            brackets = FindController.shifted(brackets, change.edits());
            view.showOccurrences(occurrences);
            view.showBrackets(brackets);
          }
          scheduleHighlights();
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Highlights follow the text only.
        }
      };

  private Bookmarks bookmarks;
  private IntConsumer commentRequest;
  private SyntaxHighlighter syntax;
  private Pairs pairs = Pairs.DEFAULT;
  private Pairs bracketPairs = Pairs.DEFAULT;
  private WordCompletion completion;
  private Consumer<String> notices;
  private int completionChars;
  private boolean autoClose = true;
  private String completionPrefix;
  private List<String> completionAll;
  private List<String> completionShown;
  private int completionSelected;
  private boolean marksWhitespace;
  private boolean marksLineEndings;
  private boolean highlightPending;
  private CancellationToken lineWork;

  /** The selections before the last Mod+press, which a drag from there adds to. */
  private SelectionSet beforeToggle;

  private CancellationToken bracketSearch;
  private CancellationToken occurrenceSearch;
  private String occurrenceKey;
  private int[] occurrences = new int[0];
  private int[] brackets = new int[0];

  /**
   * Returns the pairs auto-close inserts: the grammar's {@code autoClose}.
   *
   * @return the pairs
   */
  Pairs pairs() {
    return pairs;
  }

  /**
   * Returns the pairs bracket matching uses: the grammar's {@code brackets}.
   *
   * @return the pairs
   */
  Pairs bracketPairs() {
    return bracketPairs;
  }

  /** Returns what leaves brackets in strings and comments out of matching on the UI thread. */
  private Brackets.Filter bracketFilter(TextSnapshot text) {
    return syntax == null ? Brackets.Filter.ALL : new TokenBracketFilter(text, syntax);
  }

  /** Returns the same filter over a copy of the tokens, for a worker. */
  private Brackets.Filter workerBracketFilter(TextSnapshot text) {
    return syntax == null ? Brackets.Filter.ALL : new TokenBracketFilter(text, syntax.tokensCopy());
  }

  /**
   * Sets the document's bookmarks, shared by its views.
   *
   * @param value the bookmarks
   */
  void setBookmarks(Bookmarks value) {
    bookmarks = value;
    if (view != null) {
      view.setBookmarks(value);
    }
  }

  /** Sets what Edit Bookmark Comment passes the primary caret's line to. */
  void setCommentRequest(IntConsumer request) {
    commentRequest = request;
  }

  /** Sets the window's word completion. */
  void setCompletion(WordCompletion value) {
    completion = value;
  }

  /**
   * Sets how many word chars typed open the completion list on their own.
   *
   * @param chars the count, 0 for never
   */
  void setCompletionChars(int chars) {
    completionChars = chars;
  }

  /**
   * Turns auto-close on or off ({@code editor.autoClose}).
   *
   * @param on true when typing an opener also inserts its closer
   */
  void setAutoClose(boolean on) {
    autoClose = on;
  }

  /** Sets what shows a notice, such as a dropped line transform. */
  void setNoticeListener(Consumer<String> listener) {
    notices = listener;
  }

  // Overview strip.

  private OverviewUpdater overview;
  private boolean overviewShown = true;

  /** Sets the strip's mark computation and whether the strip shows. */
  void setOverview(OverviewUpdater updater, boolean shown) {
    if (overview != null) {
      overview.dispose();
    }
    overview = updater;
    setOverviewShown(shown);
  }

  /** Returns the strip's mark computation. */
  OverviewUpdater overview() {
    return overview;
  }

  /** Shows or hides the view's overview strip. */
  void setOverviewShown(boolean shown) {
    overviewShown = shown;
    if (view != null) {
      view.setOverviewShown(shown);
    }
    if (overview != null) {
      overview.setShown(shown);
    }
  }

  @Override
  public void overviewResized(int height) {
    if (overview != null) {
      overview.setHeight(height);
    }
  }

  /**
   * Turns the view's whitespace and line-ending marks on or off.
   *
   * @param whitespace true to mark spaces and tabs
   * @param lineEndings true to mark line breaks
   */
  void setMarks(boolean whitespace, boolean lineEndings) {
    marksWhitespace = whitespace;
    marksLineEndings = lineEndings;
    if (view != null) {
      view.setMarks(whitespace, lineEndings);
    }
  }

  /**
   * Returns the bracket highlight.
   *
   * @return model offset pairs
   */
  int[] brackets() {
    return brackets.clone();
  }

  /**
   * Returns the occurrence highlight.
   *
   * @return model offset pairs
   */
  int[] occurrences() {
    return occurrences.clone();
  }

  /**
   * Returns the completion list shown.
   *
   * @return the words, or null when no list shows
   */
  List<String> completions() {
    return completionShown;
  }

  private void disposeProductivity() {
    selections.removeListener(selectionListener);
    document.removeListener(documentListener);
    for (CancellationToken token :
        new CancellationToken[] {lineWork, bracketSearch, occurrenceSearch}) {
      if (token != null) {
        token.cancel();
      }
    }
    if (completionShown != null && completion != null) {
      completion.cancel();
    }
  }

  /** Applies an {@link AutoClose} edit; with nothing to replace, only the carets move. */
  private void applyTyped(Edit edit) {
    if (edit.batch().replacements().isEmpty()) {
      selections.set(edit.after());
      showCaret();
    } else {
      applyEdit(edit);
    }
  }

  // Line transforms.

  /** Computes a sort, duplicate removal or trim on a worker and applies it at the same revision. */
  private void transformLines(final CommandId command) {
    if (document.isReadOnly()) {
      return;
    }
    final TextSnapshot text = document.text();
    final SelectionSet set = current();
    final long revision = document.revision();
    if (lineWork != null) {
      lineWork.cancel();
    }
    final CancellationToken token = new CancellationToken();
    lineWork = token;
    if (view != null) {
      view.setBusy(true);
    }
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Edit edit = null;
              try {
                edit = lineEdit(command, text, set, token);
              } catch (CancellationException e) {
                // Esc, a newer transform, or the view closed.
              } finally {
                final Edit result = edit;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        linesComputed(token, revision, set, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      lineWork = null;
      if (view != null) {
        view.setBusy(false);
      }
    }
  }

  /** Returns the edit of a line transform command, or null when it changes nothing. */
  @WorkerThread
  static Edit lineEdit(
      CommandId command, TextSnapshot text, SelectionSet set, CancellationToken token) {
    return switch (command) {
      case SORT_LINES_ASCENDING ->
          LineTransforms.sort(text, set, LineTransforms.Order.ASCENDING, token);
      case SORT_LINES_DESCENDING ->
          LineTransforms.sort(text, set, LineTransforms.Order.DESCENDING, token);
      case SORT_LINES_IGNORE_CASE ->
          LineTransforms.sort(text, set, LineTransforms.Order.IGNORE_CASE, token);
      case REMOVE_DUPLICATE_LINES -> LineTransforms.removeDuplicates(text, set, token);
      default -> LineTransforms.trimTrailing(text, set, token);
    };
  }

  private void linesComputed(CancellationToken token, long revision, SelectionSet set, Edit edit) {
    if (token != lineWork) {
      return;
    }
    lineWork = null;
    try {
      applyLines(revision, set, edit, token.isCanceled());
    } finally {
      if (view != null) {
        view.setBusy(false);
      }
    }
  }

  private void applyLines(long revision, SelectionSet set, Edit edit, boolean canceled) {
    if (disposed || edit == null || document.isReadOnly() || canceled) {
      return;
    }
    if (document.revision() != revision) {
      if (notices != null) {
        notices.accept(UiText.LINES_STALE.text());
      }
      return;
    }
    document.clearPreedit();
    boolean moved = !set.equals(selections.selections());
    applyEdit(moved ? new Edit(edit.batch(), null) : edit);
  }

  // Bookmarks.

  private void toggleBookmarks() {
    if (bookmarks == null) {
      return;
    }
    TextSnapshot text = document.text();
    int last = -1;
    for (Selection s : current().selections()) {
      int line = text.lineAt(s.caret());
      if (line != last) {
        bookmarks.toggle(line);
        last = line;
      }
    }
  }

  private void nextBookmark(boolean forward) {
    if (bookmarks == null) {
      return;
    }
    TextSnapshot text = document.text();
    int line = bookmarks.next(text.lineAt(current().primary().caret()), forward);
    if (line >= 0) {
      jumping();
      selections.set(SelectionSet.caret(text.offsetOfLine(line)));
      showCaret();
    }
  }

  // Brackets.

  /**
   * Moves each caret beside a bracket past the bracket's partner, or selects from the bracket to
   * the partner, both included. Each partner is sought on the UI thread within {@value
   * #BRACKET_SCAN} chars of its bracket; when one lies beyond, every partner is sought on a worker
   * and the result applies only at the same revision and selections.
   */
  private void matchingBracket(final boolean select) {
    final TextSnapshot text = document.text();
    final SelectionSet set = current();
    final Pairs pairs = bracketPairs();
    int[] partners =
        partners(text, set, pairs, BRACKET_SCAN, new CancellationToken(), bracketFilter(text));
    if (partners.length > 0) {
      moveToPartners(set, partners, select);
      return;
    }
    final long revision = document.revision();
    if (bracketSearch != null) {
      bracketSearch.cancel();
    }
    final CancellationToken token = new CancellationToken();
    final Brackets.Filter filter = workerBracketFilter(text);
    bracketSearch = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final int[] found;
              try {
                found = partners(text, set, pairs, Integer.MAX_VALUE, token, filter);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (token != bracketSearch) {
                        return;
                      }
                      bracketSearch = null;
                      if (!disposed
                          && document.revision() == revision
                          && set.equals(selections.selections())) {
                        moveToPartners(set, found, select);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      bracketSearch = null;
    }
  }

  /**
   * Returns each selection's bracket and partner as pairs, -1 where none, or an empty array when a
   * partner lies more than {@code scan} chars from its bracket.
   */
  static int[] partners(
      TextSnapshot text,
      SelectionSet set,
      Pairs pairs,
      int scan,
      CancellationToken token,
      Brackets.Filter filter) {
    int[] out = new int[set.size() * 2];
    int i = 0;
    for (Selection s : set.selections()) {
      int at = Brackets.at(text, s.caret(), pairs, filter);
      int partner = -1;
      if (at >= 0) {
        long from = (long) at - scan;
        long to = (long) at + 1 + scan;
        partner =
            Brackets.match(
                text,
                at,
                pairs,
                (int) Math.max(0, from),
                (int) Math.min(text.length(), to),
                token,
                filter);
        if (partner == Brackets.BEYOND) {
          return new int[0];
        }
      }
      out[i++] = at;
      out[i++] = partner;
    }
    return out;
  }

  private void moveToPartners(SelectionSet set, int[] partners, boolean select) {
    List<Selection> out = new ArrayList<Selection>(set.size());
    boolean moved = false;
    for (int i = 0; i < set.size(); i++) {
      Selection s = set.selections().get(i);
      int at = partners[2 * i];
      int partner = partners[2 * i + 1];
      if (partner < 0) {
        out.add(s);
        continue;
      }
      moved = true;
      boolean forward = partner > at;
      if (select) {
        out.add(forward ? Selection.of(at, partner + 1) : Selection.of(at + 1, partner));
      } else {
        out.add(Selection.caret(forward ? partner + 1 : partner));
      }
    }
    if (moved) {
      selections.set(SelectionSet.of(out, set.primaryIndex()));
      showCaret();
    }
  }

  // Highlights.

  /** Recomputes the bracket and occurrence highlights on the next UI turn, once. */
  private void scheduleHighlights() {
    if (highlightPending || disposed) {
      return;
    }
    highlightPending = true;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            highlightPending = false;
            if (!disposed && view != null) {
              highlightBrackets();
              highlightOccurrences();
            }
          }
        });
  }

  private void showBrackets(int[] ranges) {
    if (view != null && (ranges.length != 0 || brackets.length != 0)) {
      brackets = ranges;
      view.showBrackets(ranges);
    }
  }

  private void showOccurrences(int[] ranges) {
    if (view != null && (ranges.length != 0 || occurrences.length != 0)) {
      occurrences = ranges;
      view.showOccurrences(ranges);
    }
  }

  private void highlightBrackets() {
    if (bracketSearch != null) {
      bracketSearch.cancel();
      bracketSearch = null;
    }
    Selection primary = current().primary();
    final TextSnapshot text = document.text();
    final Pairs pairs = bracketPairs();
    Brackets.Filter filter = bracketFilter(text);
    final int at =
        primary.isEmpty() && selections.block() == null
            ? Brackets.at(text, primary.caret(), pairs, filter)
            : -1;
    if (at < 0) {
      showBrackets(new int[0]);
      return;
    }
    int from = Math.max(0, Math.min(visibleStart, at) - BRACKET_SCAN);
    int to = (int) Math.min(text.length(), (long) Math.max(visibleEnd, at + 1) + BRACKET_SCAN);
    int partner = Brackets.match(text, at, pairs, from, to, new CancellationToken(), filter);
    if (partner != Brackets.BEYOND) {
      showBrackets(bracketRanges(at, partner));
      return;
    }
    final long revision = document.revision();
    final int caret = primary.caret();
    final CancellationToken token = new CancellationToken();
    final Brackets.Filter workerFilter = workerBracketFilter(text);
    bracketSearch = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final int found;
              try {
                found = Brackets.match(text, at, pairs, 0, text.length(), token, workerFilter);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (token != bracketSearch) {
                        return;
                      }
                      bracketSearch = null;
                      if (!disposed
                          && document.revision() == revision
                          && current().primary().caret() == caret) {
                        showBrackets(bracketRanges(at, found));
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      bracketSearch = null;
    }
  }

  private static int[] bracketRanges(int at, int partner) {
    if (partner < 0) {
      return new int[0];
    }
    int a = Math.min(at, partner);
    int b = Math.max(at, partner);
    return new int[] {a, a + 1, b, b + 1};
  }

  private void highlightOccurrences() {
    Selection primary = current().primary();
    final TextSnapshot text = document.text();
    int[] word =
        primary.isEmpty() && selections.block() == null
            ? Words.at(text, primary.caret())
            : new int[0];
    if (word.length == 0) {
      occurrenceKey = null;
      if (occurrenceSearch != null) {
        occurrenceSearch.cancel();
        occurrenceSearch = null;
      }
      showOccurrences(new int[0]);
      return;
    }
    final String needle = text.subSequence(word[0], word[1]).toString();
    final long revision = document.revision();
    final int from = visibleStart - needle.length();
    final int to = visibleEnd + needle.length();
    String key = needle + '\n' + revision + '\n' + from + '\n' + to;
    if (key.equals(occurrenceKey)) {
      return;
    }
    occurrenceKey = key;
    if (occurrenceSearch != null) {
      occurrenceSearch.cancel();
    }
    final CancellationToken token = new CancellationToken();
    occurrenceSearch = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final int[] found;
              try {
                found = Occurrences.words(text, needle, from, to, VIEW_OCCURRENCES, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (token != occurrenceSearch) {
                        return;
                      }
                      occurrenceSearch = null;
                      if (!disposed && document.revision() == revision) {
                        showOccurrences(found.length >= 4 ? found : new int[0]);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      occurrenceSearch = null;
      occurrenceKey = null;
    }
  }

  // Word completion.

  /** Returns the word chars before an offset, at most {@value #MAX_PREFIX}. */
  private String prefixAt(int caret) {
    TextSnapshot text = document.text();
    int start = caret;
    while (start > 0 && caret - start < MAX_PREFIX && isWordChar(text.charAt(start - 1))) {
      start--;
    }
    return text.subSequence(start, caret).toString();
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /** Opens the completion list after a typed word char once enough of the word is typed. */
  private void typed(String text) {
    if (completionChars > 0
        && completionShown == null
        && text.length() == 1
        && isWordChar(text.charAt(0))
        && prefixAt(current().primary().caret()).length() >= completionChars) {
      requestCompletion(false);
    }
  }

  /**
   * Looks up the words completing the one before the primary caret. An explicit request with one
   * word inserts it at once; otherwise the list shows.
   */
  private void requestCompletion(final boolean explicit) {
    Selection primary = current().primary();
    if (completion == null || selections.block() != null || !primary.isEmpty()) {
      return;
    }
    final String prefix = prefixAt(primary.caret());
    if (prefix.isEmpty() || Character.isDigit(prefix.charAt(0))) {
      return;
    }
    final long revision = document.revision();
    final int caret = primary.caret();
    completion.request(
        document,
        prefix,
        new Consumer<List<String>>() {
          @Override
          public void accept(List<String> words) {
            completed(explicit, prefix, revision, caret, words);
          }
        });
  }

  private void completed(
      boolean explicit, String prefix, long revision, int caret, List<String> words) {
    Selection primary = current().primary();
    if (disposed
        || words.isEmpty()
        || document.revision() != revision
        || !primary.isEmpty()
        || primary.caret() != caret) {
      return;
    }
    completionPrefix = prefix;
    completionAll = words;
    completionShown = words;
    completionSelected = 0;
    if (explicit && words.size() == 1) {
      acceptCompletion(0);
    } else if (view != null) {
      view.showCompletions(completionShown, 0);
    }
  }

  /** Narrows the list to the word now before the primary caret, or closes it. */
  private void refilterCompletion() {
    Selection primary = current().primary();
    String prefix =
        primary.isEmpty() && selections.block() == null ? prefixAt(primary.caret()) : "";
    if (prefix.length() < completionPrefix.length()
        || !prefix.regionMatches(true, 0, completionPrefix, 0, completionPrefix.length())) {
      closeCompletion();
      return;
    }
    List<String> shown = new ArrayList<String>();
    for (String word : completionAll) {
      if (word.regionMatches(true, 0, prefix, 0, prefix.length()) && !word.equals(prefix)) {
        shown.add(word);
      }
    }
    if (shown.isEmpty()) {
      closeCompletion();
      return;
    }
    if (!shown.equals(completionShown)) {
      completionShown = shown;
      completionSelected = 0;
    }
    if (view != null) {
      view.showCompletions(completionShown, completionSelected);
    }
  }

  /** Runs a command against the open list; returns false for a command the list ignores. */
  private boolean completionKey(CommandId command) {
    switch (command) {
      case CARET_UP -> selectCompletion(completionSelected - 1);
      case CARET_DOWN -> selectCompletion(completionSelected + 1);
      case PAGE_UP -> selectCompletion(completionSelected - COMPLETION_PAGE);
      case PAGE_DOWN -> selectCompletion(completionSelected + COMPLETION_PAGE);
      case NEWLINE, INSERT_TAB -> acceptCompletion(completionSelected);
      case CANCEL -> closeCompletion();
      case DELETE_BACK, COMPLETE_WORD -> {
        return false;
      }
      default -> {
        closeCompletion();
        return false;
      }
    }
    return true;
  }

  private void selectCompletion(int index) {
    completionSelected = Math.max(0, Math.min(completionShown.size() - 1, index));
    if (view != null) {
      view.showCompletions(completionShown, completionSelected);
    }
  }

  @Override
  public void completionChosen(int index) {
    if (completionShown != null && index >= 0 && index < completionShown.size()) {
      acceptCompletion(index);
    }
  }

  /**
   * Replaces the typed prefix before every caret that ends with it, ignoring case, with a listed
   * word, as one undo step, and closes the list.
   */
  private void acceptCompletion(int index) {
    String word = completionShown.get(index);
    String prefix = prefixAt(current().primary().caret());
    closeCompletion();
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    TextSnapshot text = document.text();
    SelectionSet set = current();
    int n = prefix.length();
    List<Replacement> out = new ArrayList<Replacement>();
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int lastEnd = 0;
    for (Selection s : set.selections()) {
      int at = s.caret();
      if (s.isEmpty()
          && at - n >= lastEnd
          && prefix.regionMatches(true, 0, text.subSequence(at - n, at).toString(), 0, n)) {
        out.add(new Replacement(at - n, n, word));
        shift += word.length() - n;
        after.add(Selection.caret(at + shift));
        lastEnd = at;
      } else {
        after.add(new Selection(s.anchor() + shift, s.caret() + shift, s.preferredColumn()));
      }
    }
    applyEdit(
        new Edit(
            EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex())));
  }

  private void closeCompletion() {
    if (completionShown == null) {
      return;
    }
    completionShown = null;
    completionAll = null;
    completionPrefix = null;
    if (view != null) {
      view.showCompletions(List.of(), 0);
    }
  }

  /** Rewrites every CR LF, CR and LF in {@code text} as {@code ending}. */
  static String convertBreaks(String text, String ending) {
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n') {
        out.append(ending);
        i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  private static List<String> splitLines(String text, String ending) {
    List<String> out = new ArrayList<String>();
    int from = 0;
    int at = text.indexOf(ending);
    while (at >= 0) {
      out.add(text.substring(from, at));
      from = at + ending.length();
      at = text.indexOf(ending, from);
    }
    out.add(text.substring(from));
    return out;
  }
}
