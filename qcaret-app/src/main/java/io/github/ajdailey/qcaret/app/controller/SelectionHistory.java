package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One view's Undo Selection and Redo Selection: the carets, selections and block it had before each
 * change, newest first, at most {@value #LIMIT}. A change that keeps every anchor of the last one's
 * selections, such as a mouse drag or Shift with an arrow key, extends that entry instead of adding
 * one. Any text change, from any view, clears both directions: the recorded offsets belong to the
 * old text. Confined to the UI thread.
 */
final class SelectionHistory {
  /** Entries kept in each direction. */
  static final int LIMIT = 100;

  private record Entry(SelectionSet set, BlockSelection block) {}

  private final SelectionModel selections;
  private final Deque<Entry> undo = new ArrayDeque<Entry>();
  private final Deque<Entry> redo = new ArrayDeque<Entry>();
  private Entry last;
  private boolean extending;
  private boolean restoring;

  private final SelectionListener recorder =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          recorded(current());
        }
      };

  private final DocumentListener clearer =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          undo.clear();
          redo.clear();
          extending = false;
          last = current();
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // The history does not depend on the dirty state.
        }
      };

  SelectionHistory(SelectionModel selections) {
    this.selections = selections;
    last = current();
    selections.addListener(recorder);
    selections.document().addListener(clearer);
  }

  /** Reports whether {@link #undo} has an entry. */
  boolean canUndo() {
    return !undo.isEmpty();
  }

  /** Reports whether {@link #redo} has an entry. */
  boolean canRedo() {
    return !redo.isEmpty();
  }

  /**
   * Restores the selections before the last change.
   *
   * @return true when an entry was restored
   */
  boolean undo() {
    return step(undo, redo);
  }

  /**
   * Restores the selections the last {@link #undo} left.
   *
   * @return true when an entry was restored
   */
  boolean redo() {
    return step(redo, undo);
  }

  /** Stops following the view and the document. */
  void dispose() {
    selections.removeListener(recorder);
    selections.document().removeListener(clearer);
  }

  private boolean step(Deque<Entry> from, Deque<Entry> to) {
    Entry target = from.pollFirst();
    if (target == null) {
      return false;
    }
    push(to, last);
    restoring = true;
    try {
      if (target.block() != null) {
        selections.setBlock(target.block(), selections.document().tabWidth());
      } else {
        selections.set(target.set());
      }
    } finally {
      restoring = false;
    }
    last = current();
    extending = false;
    return true;
  }

  private void recorded(Entry now) {
    if (restoring || now.equals(last)) {
      return;
    }
    boolean extension = keepsAnchors(last, now);
    if (!(extension && extending)) {
      push(undo, last);
    }
    extending = extension;
    redo.clear();
    last = now;
  }

  private static boolean keepsAnchors(Entry before, Entry after) {
    if (before.block() != null || after.block() != null) {
      return before.block() != null
          && after.block() != null
          && before.block().anchorLine() == after.block().anchorLine()
          && before.block().anchorColumn() == after.block().anchorColumn();
    }
    if (before.set().size() != after.set().size()) {
      return false;
    }
    for (int i = 0; i < before.set().size(); i++) {
      Selection a = before.set().selections().get(i);
      Selection b = after.set().selections().get(i);
      if (a.anchor() != b.anchor() || b.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private static void push(Deque<Entry> stack, Entry entry) {
    stack.addFirst(entry);
    if (stack.size() > LIMIT) {
      stack.removeLast();
    }
  }

  private Entry current() {
    return new Entry(selections.selections(), selections.block());
  }
}
