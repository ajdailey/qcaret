package io.github.ajdailey.qcaret.core.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Non-overlapping replacements applied as one change, in the coordinates of the text before the
 * batch. {@link Document#apply} applies them highest offset first, so each offset stays valid. Two
 * replacements may touch but not share an offset. A replacement that removes and inserts nothing is
 * dropped.
 */
public final class EditBatch {
  /** How the batch coalesces with the previous undo step. */
  public enum Kind {
    /** Its own undo step. */
    COMMAND,
    /** Typed text; merges with typing that it continues. */
    TYPING,
    /** Backspace; merges with backspaces that it continues. */
    BACKSPACE,
    /** Forward delete; merges with deletes at the same positions. */
    DELETE
  }

  private static final Comparator<Replacement> BY_OFFSET =
      new Comparator<Replacement>() {
        @Override
        public int compare(Replacement a, Replacement b) {
          return Integer.compare(a.offset(), b.offset());
        }
      };

  private final Kind kind;
  private final List<Replacement> replacements;

  private EditBatch(Kind kind, List<Replacement> replacements) {
    this.kind = kind;
    this.replacements = replacements;
  }

  /**
   * Creates a batch.
   *
   * @param kind how it coalesces
   * @param replacements the replacements, in any order
   * @return the batch, sorted by offset
   * @throws IllegalArgumentException when two replacements overlap or share an offset
   */
  public static EditBatch of(Kind kind, List<Replacement> replacements) {
    List<Replacement> sorted = new ArrayList<Replacement>(replacements.size());
    for (Replacement r : replacements) {
      if (r.removeLength() > 0 || !r.text().isEmpty()) {
        sorted.add(r);
      }
    }
    sorted.sort(BY_OFFSET);
    for (int i = 1; i < sorted.size(); i++) {
      Replacement a = sorted.get(i - 1);
      Replacement b = sorted.get(i);
      if (a.offset() == b.offset() || a.end() > b.offset()) {
        throw new IllegalArgumentException(
            "replacements at " + a.offset() + " and " + b.offset() + " overlap");
      }
    }
    return new EditBatch(kind, Collections.unmodifiableList(sorted));
  }

  /**
   * Creates a one-replacement {@link Kind#COMMAND} batch.
   *
   * @param offset the first offset replaced
   * @param removeLength the chars removed
   * @param text the chars inserted
   * @return the batch
   */
  public static EditBatch replace(int offset, int removeLength, String text) {
    return of(Kind.COMMAND, List.of(new Replacement(offset, removeLength, text)));
  }

  /**
   * Returns how the batch coalesces.
   *
   * @return the kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the replacements by ascending offset.
   *
   * @return an unmodifiable list, empty when every replacement was a no-op
   */
  public List<Replacement> replacements() {
    return replacements;
  }
}
