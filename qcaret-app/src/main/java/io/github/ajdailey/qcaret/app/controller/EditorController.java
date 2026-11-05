package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.CaretNavigation;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.BlockEdits;
import io.github.ajdailey.qcaret.core.edit.CommentEdits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineEdits;
import io.github.ajdailey.qcaret.core.edit.Moves;
import io.github.ajdailey.qcaret.core.edit.Occurrences;
import io.github.ajdailey.qcaret.core.edit.Words;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

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
 * </ul>
 *
 * <p>The controller also schedules the projection's rewraps: each runs on a {@code qcaret-cpu-}
 * worker, and a newer request cancels the running one. Confined to the UI thread.
 */
public final class EditorController implements EditorInput {
  /** Rows a page move takes without a view. */
  static final int DEFAULT_PAGE_ROWS = 20;

  private final Document document;
  private final SelectionModel selections;
  private final DisplayProjection projection;
  private final ClipboardService clipboard;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Runnable cancelSave;
  private EditorView view;
  private CancellationToken rewrap;
  private CancellationToken occurrence;
  private Runnable escapeListener;
  private boolean columnMode;
  private boolean disposed;
  private Grammar.Comments comments = Grammar.Comments.NONE;

  /**
   * Creates the controller.
   *
   * @param projection the view's display text, over the document
   * @param selections the view's selections
   * @param clipboard the system clipboard
   * @param workers runs rewraps
   * @param ui receives rewrap results
   * @param cancelSave cancels a running save of the document, for {@link #cancel}
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
  }

  /**
   * Connects the view the controller scrolls.
   *
   * @param editorView the view
   */
  public void attach(EditorView editorView) {
    view = editorView;
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
  }

  // Viewport, for find highlights.

  private int visibleStart;
  private int visibleEnd;
  private Runnable viewportListener;

  @Override
  public void viewportChanged(int start, int end) {
    int from = projection.toModel(start);
    int to = projection.toModel(end);
    if (from != visibleStart || to != visibleEnd) {
      visibleStart = from;
      visibleEnd = to;
      if (viewportListener != null) {
        viewportListener.run();
      }
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
                        scheduleRewrap();
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
    TextSnapshot text = document.text();
    int at = Math.max(0, Math.min(line, text.lineCount() - 1));
    LineView lineView = text.lineView(at, new LineView());
    int index = Columns.index(lineView, Math.max(0, column), document.tabWidth(), false);
    selections.set(SelectionSet.caret(lineView.start() + index));
    showCaret();
  }

  private SelectionSet current() {
    return selections.selections();
  }

  // Edits.

  @Override
  public void type(String text) {
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    BlockSelection block = selections.block();
    if (block != null && text.indexOf('\r') < 0 && text.indexOf('\n') < 0) {
      applyBlock(
          BlockEdits.type(
              document.text(), block, document.tabWidth(), text, EditBatch.Kind.TYPING));
      return;
    }
    replaceSelections(text, EditBatch.Kind.TYPING);
  }

  @Override
  public void newline() {
    replaceSelections(document.lineEnding().text(), EditBatch.Kind.TYPING);
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

  // Input method composition.

  @Override
  public void preeditFromView(int start, int end, String text) {
    if (document.isReadOnly()) {
      return;
    }
    int from = projection.toModel(start);
    int to = Math.max(from, projection.toModel(end));
    document.preedit(from, to - from, text);
  }

  @Override
  public void commitFromView(int count) {
    int[] range = document.preeditRange();
    if (range == null || count <= 0 || document.isReadOnly()) {
      return;
    }
    String composed = document.text().subSequence(range[0], range[0] + range[1]).toString();
    int n = Math.min(count, composed.length());
    String rest = composed.substring(n);
    document.clearPreedit();
    document.apply(
        EditBatch.of(
            EditBatch.Kind.COMMAND,
            List.of(new Replacement(range[0], 0, composed.substring(0, n)))),
        selections,
        SelectionSet.caret(range[0] + n));
    if (!rest.isEmpty()) {
      document.preedit(range[0] + n, 0, rest);
    }
    showCaret();
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
          from = Math.max(lastEnd, Moves.left(text, s.start()));
        } else {
          to = Moves.right(text, s.end());
        }
      } else {
        anySelected = true;
      }
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
