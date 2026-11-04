package io.github.ajdailey.qcaret.core.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable carets and selections, sorted by start, with one primary. Selections that overlap or
 * touch are merged when the set is built, so the set never holds two that share an offset.
 */
public final class SelectionSet {
  private final List<Selection> selections;
  private final int primary;

  private SelectionSet(List<Selection> selections, int primary) {
    this.selections = selections;
    this.primary = primary;
  }

  /**
   * Builds a set: sorts the selections and merges those that overlap or touch. A merged selection
   * keeps the direction of the earliest one in it and drops the preferred column.
   *
   * @param selections at least one selection, in any order
   * @param primary the index in {@code selections} of the primary
   * @return the set; the primary is the merged selection holding the given one
   */
  public static SelectionSet of(List<Selection> selections, int primary) {
    if (selections.isEmpty()) {
      throw new IllegalArgumentException("no selection");
    }
    Objects.checkIndex(primary, selections.size());
    Selection chosen = selections.get(primary);
    List<Selection> sorted = new ArrayList<Selection>(selections);
    sorted.sort(
        new Comparator<Selection>() {
          @Override
          public int compare(Selection a, Selection b) {
            int c = Integer.compare(a.start(), b.start());
            return c != 0 ? c : Integer.compare(a.end(), b.end());
          }
        });
    List<Selection> out = new ArrayList<Selection>(sorted.size());
    int primaryOut = -1;
    Selection current = sorted.get(0);
    boolean holdsPrimary = current == chosen;
    for (int i = 1; i < sorted.size(); i++) {
      Selection next = sorted.get(i);
      if (next.start() <= current.end()) {
        int end = Math.max(current.end(), next.end());
        if (end != current.end()) {
          current =
              current.anchor() <= current.caret()
                  ? Selection.of(current.start(), end)
                  : Selection.of(end, current.start());
        }
        holdsPrimary |= next == chosen;
      } else {
        if (holdsPrimary) {
          primaryOut = out.size();
        }
        out.add(current);
        current = next;
        holdsPrimary = current == chosen;
      }
    }
    if (holdsPrimary) {
      primaryOut = out.size();
    }
    out.add(current);
    return new SelectionSet(Collections.unmodifiableList(out), primaryOut);
  }

  /**
   * Creates a set of one caret.
   *
   * @param offset the caret offset
   * @return the set
   */
  public static SelectionSet caret(int offset) {
    return new SelectionSet(List.of(Selection.caret(offset)), 0);
  }

  /**
   * Returns the selections by start.
   *
   * @return an unmodifiable list of at least one selection
   */
  public List<Selection> selections() {
    return selections;
  }

  /**
   * Returns the number of selections.
   *
   * @return the count
   */
  public int size() {
    return selections.size();
  }

  /**
   * Returns the primary's index.
   *
   * @return an index into {@link #selections}
   */
  public int primaryIndex() {
    return primary;
  }

  /**
   * Returns the primary selection.
   *
   * @return the selection the caret navigation and the status bar follow
   */
  public Selection primary() {
    return selections.get(primary);
  }

  /** Maps every selection through a change and merges those that come to touch. */
  SelectionSet map(ChangeMap map) {
    List<Selection> out = new ArrayList<Selection>(selections.size());
    for (Selection s : selections) {
      out.add(s.map(map));
    }
    return of(out, primary);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SelectionSet other
        && primary == other.primary
        && selections.equals(other.selections);
  }

  @Override
  public int hashCode() {
    return 31 * selections.hashCode() + primary;
  }

  @Override
  public String toString() {
    return "SelectionSet" + selections + " primary " + primary;
  }
}
