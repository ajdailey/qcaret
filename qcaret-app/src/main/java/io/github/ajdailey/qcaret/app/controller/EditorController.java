package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.CaretNavigation;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.IndentRules;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.editor.PatternIndentRules;
import io.github.ajdailey.qcaret.app.editor.SmartIndent;
import io.github.ajdailey.qcaret.app.editor.TokenBracketFilter;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.BusyCounter;
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
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.AutoClose;
import io.github.ajdailey.qcaret.core.edit.BlockEdits;
import io.github.ajdailey.qcaret.core.edit.Brackets;
import io.github.ajdailey.qcaret.core.edit.CaseEdits;
import io.github.ajdailey.qcaret.core.edit.CommentEdits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineEdits;
import io.github.ajdailey.qcaret.core.edit.Moves;
import io.github.ajdailey.qcaret.core.edit.Occurrences;
import io.github.ajdailey.qcaret.core.edit.Pairs;
import io.github.ajdailey.qcaret.core.edit.Transpose;
import io.github.ajdailey.qcaret.core.edit.WordDeletes;
import io.github.ajdailey.qcaret.core.edit.Words;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

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
 *       older revision is dropped. Select All Occurrences, Skip Occurrence, Expand Selection,
 *       Select Line, Undo Selection and Redo Selection run in {@link SelectionCommands}.
 *   <li>Input method composition text applies outside undo ({@link Document#preedit}); any edit
 *       first removes it.
 *   <li>Toggle Line Comment and Toggle Block Comment use the document grammar's delimiters ({@link
 *       CommentEdits}), at every selection, as one undo step.
 *   <li>Enter and typed closers indent through {@link SmartIndent} and the view's {@link
 *       IndentRules}.
 *   <li>Folding ({@link FoldScheduling}): the fold commands act on the primary caret's line. Left,
 *       Right, word and document-end moves step over hidden lines.
 *   <li>Typing and Backspace follow {@link AutoClose} with {@link #pairs()} unless {@code
 *       editor.autoClose} is off.
 *   <li>Sort, Remove Duplicate Lines, Trim Trailing Whitespace and Wrap Paragraph run in {@link
 *       LineTransformRuns}.
 *   <li>{@link EditorHighlights} recomputes the view's bracket and occurrence highlights after a
 *       caret move, an edit or a scroll.
 *   <li>Word completion ({@link CompletionInput}) shows its list in the view; while it shows, Up,
 *       Down, Page Up and Page Down move in the list, Enter and Tab insert the selected word at
 *       every caret, Esc closes it, and typing narrows it.
 * </ul>
 *
 * <p>{@link FoldScheduling} runs the projection's rewraps and fold region updates. Confined to the
 * UI thread.
 */
public final class EditorController implements EditorInput {
  /** Rows a page move takes without a view. */
  static final int DEFAULT_PAGE_ROWS = 20;

  private final Document document;
  private final SelectionModel selections;
  private final DisplayProjection projection;
  private ClipboardService clipboard;
  private final Workers workers;
  private final UiDispatcher ui;
  private Runnable cancelSave;
  private EditorView view;
  private CancellationToken occurrence;

  /** The view's busy cursor for Add Next Occurrence, Go to Matching Bracket and Fold All. */
  private final BusyCounter busy;

  private final FoldScheduling folds;
  private final EditorHighlights highlights;
  private final SelectionCommands selectionCommands;
  private final CompletionInput completions;
  private final LineTransformRuns lineRuns;
  private Runnable escapeListener;
  private boolean columnMode;
  private String highSurrogate;
  private boolean disposed;
  private Grammar.Comments comments = Grammar.Comments.NONE;
  private IndentRules indentRules = IndentRules.BRACKETS;

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
    busy =
        new BusyCounter(
            ui,
            new BusyCounter.Indicator() {
              @Override
              public void busyChanged(boolean on) {
                if (view != null) {
                  view.setBusy(on);
                }
              }
            });
    folds = new FoldScheduling(this, workers, ui);
    highlights = new EditorHighlights(this, workers, ui);
    completions = new CompletionInput(this);
    selections.addListener(selectionListener);
    selectionCommands = new SelectionCommands(this, workers, ui);
    lineRuns = new LineTransformRuns(this, workers, ui);
    lineChanges =
        new LineChangesUpdater(
            document,
            workers,
            ui,
            new Supplier<EditorView>() {
              @Override
              public EditorView get() {
                return view;
              }
            });
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
    highlights.viewAttached();
    lineChanges.viewAttached();
  }

  /** Lets go of the view before its tab moves to another window; closes the completion list. */
  void releaseView() {
    completions.release();
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
      folds.dispose();
      if (occurrence != null) {
        occurrence.cancel();
      }
      disposeProductivity();
      selectionCommands.dispose();
      if (overview != null) {
        overview.dispose();
      }
      lineChanges.dispose();
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
    folds.scheduleRewrap();
  }

  @Override
  public void viewColumnsChanged(int columns) {
    projection.setViewColumns(columns);
    folds.scheduleRewrap();
  }

  /** Applies the document's tab width to the rows and the view. */
  void tabWidthChanged() {
    projection.setTabWidth(document.tabWidth());
    folds.scheduleRewrap();
    if (view != null) {
      view.setTabWidth(document.tabWidth());
    }
    folds.tabWidthChanged();
  }

  // Viewport, for find highlights.

  private int visibleStart;
  private int visibleEnd;
  private Runnable viewportListener;

  @Override
  public void viewportChanged(int start, int end) {
    folds.viewportChanged();
    int from = projection.toModel(start);
    int to = projection.toModel(end);
    if (from != visibleStart || to != visibleEnd) {
      visibleStart = from;
      visibleEnd = to;
      if (viewportListener != null) {
        viewportListener.run();
      }
      highlights.schedule();
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

  /**
   * Puts back a view's remembered state. The caret and top line apply now; each fold applies once a
   * region starts on its line, and the view scrolls to the top line again after it.
   *
   * @param set the selections, or null to keep them
   * @param top the zero-based first visible line
   * @param collapsed the first lines of the regions to collapse
   */
  void restoreView(SelectionSet set, int top, int[] collapsed) {
    if (set != null) {
      selections.set(set);
    }
    folds.restore(Math.max(0, Math.min(top, document.text().lineCount() - 1)), collapsed);
  }

  /**
   * Returns the first visible line.
   *
   * @return the zero-based line, the restored one until the view reports
   */
  int topLine() {
    int restored = folds.restoredTop();
    return restored >= 0 ? restored : document.text().lineAt(visibleStart);
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

  // Commands.

  @Override
  public boolean execute(CommandId command) {
    if (!document.isReadOnly()) {
      document.clearPreedit();
    }
    if (completions.key(command)) {
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
      case FOLD, UNFOLD, TOGGLE_FOLD, FOLD_ALL, UNFOLD_ALL -> folds.fold(command);
      case SORT_LINES_ASCENDING,
          SORT_LINES_DESCENDING,
          SORT_LINES_IGNORE_CASE,
          REMOVE_DUPLICATE_LINES,
          TRIM_TRAILING_WHITESPACE ->
          lineRuns.transformLines(command);
      case COMPLETE_WORD -> completions.requestCompletion(true);
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
      case SELECT_ALL_OCCURRENCES,
          SKIP_OCCURRENCE,
          EXPAND_SELECTION,
          SELECT_LINE,
          UNDO_SELECTION,
          REDO_SELECTION ->
          selectionCommands.execute(command);
      case DELETE_WORD_BACK, DELETE_WORD_FORWARD ->
          applyEdit(
              WordDeletes.delete(
                  document.text(), current(), command == CommandId.DELETE_WORD_BACK));
      case TITLE_CASE ->
          applyEdit(CaseEdits.convert(document.text(), current(), CaseEdits.Mode.TITLE));
      case SWAP_CASE ->
          applyEdit(CaseEdits.convert(document.text(), current(), CaseEdits.Mode.SWAP));
      case TRANSPOSE -> applyEdit(Transpose.transpose(document.text(), current()));
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
   * Takes the document grammar's comments, fold mode, indent rules and pairs. A {@code brackets},
   * {@code markers} or {@code sections} grammar's fold regions come from the document's
   * highlighter; every other grammar folds by indentation merged with the highlighter's span and
   * region marker regions, and plain text by indentation alone.
   *
   * @param grammar the grammar, or null for plain text
   */
  void setGrammar(Grammar grammar) {
    comments = grammar == null ? Grammar.Comments.NONE : grammar.comments();
    Grammar.FoldMode mode = grammar == null ? Grammar.FoldMode.NONE : grammar.folding().mode();
    boolean own =
        mode == Grammar.FoldMode.BRACKETS
            || mode == Grammar.FoldMode.MARKERS
            || mode == Grammar.FoldMode.SECTIONS;
    boolean highlighted = grammar != null && syntax != null && syntax.syntax() != null;
    folds.setSource(own || highlighted ? syntax : null, !own);
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
   * matching and whose fold regions a grammar folds by.
   *
   * @param highlighter the highlighter
   */
  void setSyntax(SyntaxHighlighter highlighter) {
    syntax = highlighter;
  }

  /** Returns the document's compiled grammar; null for plain text and before one is chosen. */
  Syntax compiledSyntax() {
    return syntax == null ? null : syntax.syntax();
  }

  /** Reports whether the editor is disposed. */
  boolean disposed() {
    return disposed;
  }

  /** Returns the counter behind the view's busy cursor for this editor's worker commands. */
  ActivityIndicator busyCursor() {
    return busy;
  }

  /** Runs the fold step again when the regions come from the highlighter, which has new ones. */
  void syntaxRegionsChanged() {
    folds.sourceChanged(syntax);
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
    if (disposed || document.isReadOnly()) {
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
    completions.typed(text);
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

  /**
   * Inserts text at every caret, replacing each selection, as one undo step; a block takes it on
   * every row, as a typed text.
   *
   * @param text text without line breaks
   */
  void insertText(String text) {
    if (text.isEmpty() || document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    BlockSelection block = selections.block();
    if (block != null) {
      applyBlock(
          BlockEdits.type(
              document.text(), block, document.tabWidth(), text, EditBatch.Kind.COMMAND));
    } else {
      replaceSelections(text, EditBatch.Kind.COMMAND);
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
    lineRuns.cancel();
    selectionCommands.cancel();
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
  public void selectWordFromView(int offset, boolean onChar) {
    TextSnapshot text = document.text();
    int at = projection.toModel(offset);
    if (onChar && (at >= text.length() || !Words.isWordAt(text, at))) {
      return;
    }
    int[] word = Words.at(text, at);
    if (word.length > 0) {
      selections.set(SelectionSet.of(List.of(Selection.of(word[0], word[1])), 0));
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
    if (!disposed && !document.isReadOnly()) {
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
   * Sets the rules smart indent follows.
   *
   * @param rules the rules
   */
  public void setIndentRules(IndentRules rules) {
    indentRules = rules;
  }

  @Override
  public void toggleFoldFromView(int row) {
    folds.toggleFromView(row);
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
   * the new primary, apart from one it touches. The search runs on a worker.
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
    boolean started =
        new BusyWork(ui, busy) {
          @Override
          @WorkerThread
          void work() {
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
        }.startCpu(workers);
    if (!started) {
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
    selections.set(SelectionSet.apart(list, list.size() - 1));
    showCaret();
  }

  // Applying edits.

  void showCaret() {
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
  void applyEdit(Edit edit) {
    if (edit == null || document.isReadOnly()) {
      return;
    }
    if (!edit.batch().replacements().isEmpty()) {
      document.apply(edit.batch(), selections, edit.after());
      showCaret();
    }
    if (edit.unchanged() > 0) {
      notice(UnchangedLines.notice(edit.unchanged()));
    }
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

  // Productivity: auto-close, line transforms, bookmarks, bracket matching, completion.

  /** Chars scanned on each side of the visible range for a bracket's partner on the UI thread. */
  static final int BRACKET_SCAN = 1 << 14;

  private final SelectionListener selectionListener =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          completions.selectionsChanged();
          highlights.schedule();
        }
      };

  private Bookmarks bookmarks;
  private IntConsumer commentRequest;
  private SyntaxHighlighter syntax;
  private Pairs pairs = Pairs.DEFAULT;
  private Pairs bracketPairs = Pairs.DEFAULT;
  private Consumer<String> notices;
  private boolean autoClose = true;
  private boolean marksWhitespace;
  private boolean marksLineEndings;

  /** The selections before the last Mod+press, which a drag from there adds to. */
  private SelectionSet beforeToggle;

  private CancellationToken partnerSearch;

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
  Brackets.Filter bracketFilter(TextSnapshot text) {
    return syntax == null ? Brackets.Filter.ALL : new TokenBracketFilter(text, syntax);
  }

  /** Returns the same filter over a copy of the tokens, for a worker. */
  Brackets.Filter workerBracketFilter(TextSnapshot text) {
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
    completions.setCompletion(value);
  }

  /**
   * Sets how many word chars typed open the completion list on their own.
   *
   * @param chars the count, 0 for never
   */
  void setCompletionChars(int chars) {
    completions.setCompletionChars(chars);
  }

  @Override
  public void completionChosen(int index) {
    completions.chosen(index);
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

  /** Shows a notice through the listener, if one is set. */
  void notice(String text) {
    if (notices != null) {
      notices.accept(text);
    }
  }

  /** Returns the view's Undo Selection and Redo Selection history. */
  SelectionHistory selectionHistory() {
    return selectionCommands.history();
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

  // Modified-line marks.

  private final LineChangesUpdater lineChanges;

  @Override
  public void lineChangesShown(boolean shown) {
    lineChanges.setShown(shown);
  }

  /** Returns the modified-line marks shown last. */
  LineChanges lineChanges() {
    return lineChanges.changes();
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

  private void disposeProductivity() {
    selections.removeListener(selectionListener);
    highlights.dispose();
    lineRuns.cancel();
    if (partnerSearch != null) {
      partnerSearch.cancel();
    }
    completions.cancelLookup();
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

  /**
   * Runs Wrap Paragraph with the grammar's line comment, as a line transform.
   *
   * @param column the widest line, in columns
   */
  void wrapParagraph(int column) {
    lineRuns.wrapParagraph(column);
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
    if (partnerSearch != null) {
      partnerSearch.cancel();
    }
    final CancellationToken token = new CancellationToken();
    final Brackets.Filter filter = workerBracketFilter(text);
    partnerSearch = token;
    boolean started =
        new BusyWork(ui, busy) {
          @Override
          @WorkerThread
          void work() {
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
                    if (token != partnerSearch) {
                      return;
                    }
                    partnerSearch = null;
                    if (!disposed
                        && document.revision() == revision
                        && set.equals(selections.selections())) {
                      moveToPartners(set, found, select);
                    }
                  }
                });
          }
        }.startCpu(workers);
    if (!started) {
      partnerSearch = null;
    }
  }

  /**
   * Returns each selection's bracket and partner as pairs, -1 where none, or an empty array when a
   * partner lies more than {@code scan} chars from its bracket.
   */
  private static int[] partners(
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
