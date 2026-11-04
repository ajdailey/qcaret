package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs one editor view's input against its document: every edit is one {@link Document#apply} with
 * the view's {@link SelectionModel} as origin, and undo and redo restore that model. Typing,
 * Backspace and Delete apply at every selection and coalesce into undo steps by kind; cut, paste
 * and edits the widget made on its own are commands. A read-only document ignores edits. After each
 * edit the view scrolls to the primary caret.
 *
 * <p>The controller also schedules the projection's rewraps: each runs on a {@code qcaret-cpu-}
 * worker, and a newer request cancels the running one. Confined to the UI thread.
 */
public final class EditorController implements EditorInput {
  private final Document document;
  private final SelectionModel selections;
  private final DisplayProjection projection;
  private final ClipboardService clipboard;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Runnable cancelSave;
  private TabView view;
  private CancellationToken rewrap;
  private boolean disposed;

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
   * @param tab the view
   */
  public void attach(TabView tab) {
    view = tab;
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

  // Edits.

  @Override
  public boolean execute(CommandId command) {
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
        return false;
      }
    }
    return true;
  }

  @Override
  public void type(String text) {
    replaceSelections(text, EditBatch.Kind.TYPING);
  }

  @Override
  public void newline() {
    replaceSelections(document.lineEnding().text(), EditBatch.Kind.TYPING);
  }

  @Override
  public void backspace() {
    deleteAround(true);
  }

  @Override
  public void deleteForward() {
    deleteAround(false);
  }

  @Override
  public void cut() {
    if (copySelections() && !document.isReadOnly()) {
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
    String converted = convertBreaks(text, document.lineEnding().text());
    SelectionSet set = selections.selections();
    List<String> parts = splitLines(converted, document.lineEnding().text());
    if (set.size() > 1 && parts.size() == set.size()) {
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
    if (set.size() > 1) {
      selections.set(SelectionSet.of(List.of(set.primary()), 0));
    }
  }

  @Override
  public void replaceFromView(int start, int end, String text) {
    if (document.isReadOnly()) {
      return;
    }
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
    if (!set.equals(selections.selections())) {
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

  private void showCaret() {
    if (view != null) {
      view.showCaret();
    }
  }

  /** Copies the selected text, one selection per line; returns false when nothing is selected. */
  private boolean copySelections() {
    TextSnapshot text = document.text();
    StringBuilder out = new StringBuilder();
    boolean any = false;
    for (Selection s : selections.selections().selections()) {
      if (s.isEmpty()) {
        continue;
      }
      if (any) {
        out.append(document.lineEnding().text());
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
          from = Math.max(lastEnd, s.start() - charBefore(text, s.start()));
        } else {
          to = s.end() + charAfter(text, s.end());
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

  private static int charBefore(TextSnapshot text, int offset) {
    if (offset == 0) {
      return 0;
    }
    char c = text.charAt(offset - 1);
    if (offset >= 2) {
      char p = text.charAt(offset - 2);
      if ((c == '\n' && p == '\r')
          || (Character.isLowSurrogate(c) && Character.isHighSurrogate(p))) {
        return 2;
      }
    }
    return 1;
  }

  private static int charAfter(TextSnapshot text, int offset) {
    if (offset >= text.length()) {
      return 0;
    }
    char c = text.charAt(offset);
    if (offset + 1 < text.length()) {
      char n = text.charAt(offset + 1);
      if ((c == '\r' && n == '\n')
          || (Character.isHighSurrogate(c) && Character.isLowSurrogate(n))) {
        return 2;
      }
    }
    return 1;
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
