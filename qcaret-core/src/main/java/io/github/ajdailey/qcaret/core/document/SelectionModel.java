package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * One view's carets and selections in a {@link Document}, or a rectangular block. The document maps
 * them through every change, so a change from any source leaves them valid. Use it on the
 * document's thread.
 */
public final class SelectionModel {
  private final Document document;
  private final List<SelectionListener> listeners = new ArrayList<SelectionListener>();
  private SelectionSet selections = SelectionSet.caret(0);
  private BlockSelection block;
  private int tabWidth = 1;
  private boolean disposed;

  SelectionModel(Document document) {
    this.document = document;
  }

  /**
   * Returns the document.
   *
   * @return the document whose changes map this model
   */
  public Document document() {
    return document;
  }

  /**
   * Returns the selections; in block mode, the block's rows.
   *
   * @return the set
   */
  public SelectionSet selections() {
    return selections;
  }

  /**
   * Returns the block.
   *
   * @return the rectangular selection, or null when not in block mode
   */
  public BlockSelection block() {
    return block;
  }

  /**
   * Sets the selections after a caret move or a selection by the user, leaving block mode. Typing
   * that follows starts a new undo step.
   *
   * @param set the selections, inside the text
   * @throws IndexOutOfBoundsException when a selection ends past the text
   */
  public void set(SelectionSet set) {
    document.checkThread();
    checkBounds(set);
    document.breakCoalescing();
    block = null;
    update(set);
  }

  /**
   * Enters or moves a block selection, as {@link #set}.
   *
   * @param selection the block; its lines must exist
   * @param tabWidth columns per tab stop
   * @throws IndexOutOfBoundsException when a line does not exist
   */
  public void setBlock(BlockSelection selection, int tabWidth) {
    document.checkThread();
    int last = document.text().lineCount() - 1;
    if (Math.max(selection.anchorLine(), selection.caretLine()) > last) {
      throw new IndexOutOfBoundsException("line past " + last);
    }
    document.breakCoalescing();
    this.block = selection;
    this.tabWidth = tabWidth;
    update(selection.toSelections(document.text(), tabWidth));
  }

  /**
   * Adds a listener.
   *
   * @param listener called after each change
   */
  public void addListener(SelectionListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added before
   */
  public void removeListener(SelectionListener listener) {
    listeners.remove(listener);
  }

  /** Detaches the model from its document; later changes do not map it. */
  public void dispose() {
    if (!disposed) {
      disposed = true;
      document.removeSelectionModel(this);
    }
  }

  /**
   * Sets the selections an edit or an undo step left, keeping block mode when the set is the
   * block's.
   */
  void restore(SelectionSet set) {
    if (block != null && !set.equals(block.toSelections(document.text(), tabWidth))) {
      block = null;
    }
    update(set);
  }

  /** Enters block mode with the block an edit left, at the tab width of the last block. */
  void restoreBlock(BlockSelection selection) {
    block = selection;
    update(selection.toSelections(document.text(), tabWidth));
  }

  /** Maps the selections, or the block's lines, through a change. */
  void map(ChangeMap map, TextSnapshot before) {
    if (block != null) {
      block =
          new BlockSelection(
              mapLine(block.anchorLine(), map, before),
              block.anchorColumn(),
              mapLine(block.caretLine(), map, before),
              block.caretColumn());
      update(block.toSelections(document.text(), tabWidth));
    } else {
      update(selections.map(map));
    }
  }

  private int mapLine(int line, ChangeMap map, TextSnapshot before) {
    return document.text().lineAt(map.map(before.offsetOfLine(line), Bias.AFTER));
  }

  private void update(SelectionSet set) {
    boolean changed = !set.equals(selections);
    selections = set;
    if (changed) {
      document.selectionsChanged(this);
    }
  }

  void fire() {
    for (SelectionListener listener : new ArrayList<SelectionListener>(listeners)) {
      listener.selectionsChanged(this);
    }
  }

  private void checkBounds(SelectionSet set) {
    int length = document.text().length();
    for (Selection s : set.selections()) {
      if (s.end() > length) {
        throw new IndexOutOfBoundsException("selection end " + s.end() + " past " + length);
      }
    }
  }
}
