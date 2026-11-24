package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.EditorInput;

/**
 * The input of a pane {@link StagedTabs} builds before its editor attaches: forwards every call to
 * the editor, except the pane's size, viewport, overview height and modified-line reports, which
 * would apply the hidden pane's state to the pane the editor shows. Until {@link #open} it keeps
 * the last of each; {@link #open} forwards them and every later call. Confined to the UI thread.
 */
final class StagedInput implements EditorInput {
  private final EditorController editor;
  private boolean open;
  private int columns;
  private int[] viewport;
  private int overview = -1;
  private Boolean lineChanges;

  StagedInput(EditorController editor) {
    this.editor = editor;
  }

  /**
   * Returns the editor a staged pane's input forwards to.
   *
   * @param input a pane's input
   * @return its editor, or the input itself when it is not staged
   */
  static EditorInput editor(EditorInput input) {
    return input instanceof StagedInput staged ? staged.editor : input;
  }

  /** Forwards the reports kept so far and every later call. */
  void open() {
    open = true;
    if (columns > 0) {
      editor.viewColumnsChanged(columns);
    }
    if (overview >= 0) {
      editor.overviewResized(overview);
    }
    if (lineChanges != null) {
      editor.lineChangesShown(lineChanges);
    }
    if (viewport != null) {
      editor.viewportChanged(viewport[0], viewport[1]);
    }
  }

  @Override
  public void viewColumnsChanged(int value) {
    if (open) {
      editor.viewColumnsChanged(value);
    } else {
      columns = value;
    }
  }

  @Override
  public void viewportChanged(int start, int end) {
    if (open) {
      editor.viewportChanged(start, end);
    } else {
      viewport = new int[] {start, end};
    }
  }

  @Override
  public void overviewResized(int height) {
    if (open) {
      editor.overviewResized(height);
    } else {
      overview = height;
    }
  }

  @Override
  public void lineChangesShown(boolean shown) {
    if (open) {
      editor.lineChangesShown(shown);
    } else {
      lineChanges = shown;
    }
  }

  @Override
  public void type(String text) {
    editor.type(text);
  }

  @Override
  public boolean execute(CommandId command) {
    return editor.execute(command);
  }

  @Override
  public void newline() {
    editor.newline();
  }

  @Override
  public void backspace() {
    editor.backspace();
  }

  @Override
  public void deleteForward() {
    editor.deleteForward();
  }

  @Override
  public void cut() {
    editor.cut();
  }

  @Override
  public void copy() {
    editor.copy();
  }

  @Override
  public void paste() {
    editor.paste();
  }

  @Override
  public void undo() {
    editor.undo();
  }

  @Override
  public void redo() {
    editor.redo();
  }

  @Override
  public void selectAll() {
    editor.selectAll();
  }

  @Override
  public void cancel() {
    editor.cancel();
  }

  @Override
  public void replaceFromView(int start, int end, String text) {
    editor.replaceFromView(start, end, text);
  }

  @Override
  public void selectFromView(int anchor, int caret) {
    editor.selectFromView(anchor, caret);
  }

  @Override
  public void selectWordFromView(int offset, boolean onChar) {
    editor.selectWordFromView(offset, onChar);
  }

  @Override
  public void selectionsFromView(int[] ranges, int primary) {
    editor.selectionsFromView(ranges, primary);
  }

  @Override
  public void menuFromView(int offset) {
    editor.menuFromView(offset);
  }

  @Override
  public void blockFromView(int anchorX, int anchorY, int x, int y) {
    editor.blockFromView(anchorX, anchorY, x, y);
  }

  @Override
  public void extendBlockFromView(int x, int y) {
    editor.extendBlockFromView(x, y);
  }

  @Override
  public void toggleCaretFromView(int offset) {
    editor.toggleCaretFromView(offset);
  }

  @Override
  public void addSelectionFromView(int anchor, int caret) {
    editor.addSelectionFromView(anchor, caret);
  }

  @Override
  public void preeditFromView(String text) {
    editor.preeditFromView(text);
  }

  @Override
  public void toggleFoldFromView(int row) {
    editor.toggleFoldFromView(row);
  }

  @Override
  public void completionChosen(int index) {
    editor.completionChosen(index);
  }
}
