package io.github.ajdailey.qcaret.core.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Undo and redo steps of one document. A step holds its batches with their removed text and the
 * selections before and after it. A new step discards the redo steps.
 */
final class UndoHistory {
  /** One undo step: a coalesced edit, a command or a group of batches. */
  static final class Step {
    final List<List<Change>> batches = new ArrayList<List<Change>>();
    final EditBatch.Kind kind;
    final SelectionSet before;
    final long stateBefore;
    SelectionSet after;
    long stateAfter;
    long nanos;
    long bytes;
    boolean open;

    Step(EditBatch.Kind kind, SelectionSet before, long stateBefore) {
      this.kind = kind;
      this.before = before;
      this.stateBefore = stateBefore;
    }

    void add(List<Change> batch) {
      batches.add(batch);
      bytes += Change.bytes(batch);
    }
  }

  private final UndoLimits limits;
  private final ArrayDeque<Step> undo = new ArrayDeque<Step>();
  private final ArrayDeque<Step> redo = new ArrayDeque<Step>();
  private long bytes;

  UndoHistory(UndoLimits limits) {
    this.limits = limits;
  }

  Step top() {
    return undo.peekLast();
  }

  boolean canUndo() {
    return !undo.isEmpty();
  }

  boolean canRedo() {
    return !redo.isEmpty();
  }

  /** Adds a step, discards the redo steps and drops the oldest steps past the limits. */
  void push(Step step) {
    for (Step s : redo) {
      bytes -= s.bytes;
    }
    redo.clear();
    undo.addLast(step);
    bytes += step.bytes;
    trim();
  }

  /** Replaces the top step's only batch with a coalesced one. */
  void replaceTop(List<Change> merged) {
    Step top = undo.peekLast();
    bytes -= top.bytes;
    top.batches.clear();
    top.bytes = 0;
    top.add(merged);
    bytes += top.bytes;
    trim();
  }

  Step undo() {
    Step step = undo.removeLast();
    redo.addLast(step);
    return step;
  }

  Step redo() {
    Step step = redo.removeLast();
    undo.addLast(step);
    return step;
  }

  int steps() {
    return undo.size() + redo.size();
  }

  long bytes() {
    return bytes;
  }

  private void trim() {
    while (undo.size() > 1
        && (undo.size() + redo.size() > limits.maxSteps() || bytes > limits.maxBytes())) {
      bytes -= undo.removeFirst().bytes;
    }
  }

  /**
   * Coalesces a batch with the previous one when it continues it at every position: typing right
   * after the text typed there, a backspace right before the text deleted there, a forward delete
   * at the same position. Typed whitespace after a word char does not continue it.
   *
   * @param kind the kind both batches have
   * @param prev the previous batch, in the coordinates before it
   * @param next the new batch, in the coordinates after {@code prev}
   * @return the combined batch in the coordinates before {@code prev}, or null
   */
  static List<Change> merge(EditBatch.Kind kind, List<Change> prev, List<Change> next) {
    int n = prev.size();
    if (next.size() != n || kind == EditBatch.Kind.COMMAND) {
      return null;
    }
    List<Change> out = new ArrayList<Change>(n);
    int shift = 0;
    int previousEnd = 0;
    for (int i = 0; i < n; i++) {
      Change p = prev.get(i);
      Change c = next.get(i);
      int start = p.offset() + shift;
      int end = start + p.inserted().length();
      int nextStart =
          i + 1 < n
              ? prev.get(i + 1).offset() + shift + p.inserted().length() - p.removed().length()
              : Integer.MAX_VALUE;
      Change merged;
      switch (kind) {
        case TYPING:
          if (!c.removed().isEmpty()
              || c.offset() != end
              || breaksWord(p.inserted(), c.inserted())) {
            return null;
          }
          merged = new Change(p.offset(), p.removed(), p.inserted() + c.inserted());
          break;
        case BACKSPACE:
          if (!p.inserted().isEmpty()
              || !c.inserted().isEmpty()
              || c.offset() + c.removed().length() != start
              || c.offset() < previousEnd) {
            return null;
          }
          merged = new Change(c.offset() - shift, c.removed() + p.removed(), "");
          break;
        default:
          if (!p.inserted().isEmpty()
              || !c.inserted().isEmpty()
              || c.offset() != start
              || c.offset() + c.removed().length() > nextStart) {
            return null;
          }
          merged = new Change(p.offset(), p.removed() + c.removed(), "");
          break;
      }
      out.add(merged);
      shift += p.inserted().length() - p.removed().length();
      previousEnd = end;
    }
    return out;
  }

  private static boolean breaksWord(String before, String typed) {
    return !before.isEmpty()
        && Character.isWhitespace(typed.charAt(0))
        && !Character.isWhitespace(before.charAt(before.length() - 1));
  }
}
