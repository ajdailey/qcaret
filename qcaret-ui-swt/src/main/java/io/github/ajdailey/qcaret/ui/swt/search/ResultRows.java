package io.github.ajdailey.qcaret.ui.swt.search;

import io.github.ajdailey.qcaret.app.view.ResultsInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The results dock's tree as a list of rows: each set's row, then, while the set is expanded, each
 * file group's row followed, while the group is expanded, by its match rows. Maps a row index to
 * its path ({@code {set}}, {@code {set, file}} or {@code {set, file, match}}) and back. A set is
 * expanded when it first appears; groups start collapsed, and a set's groups collapse again when
 * its search ends, since the groups are reordered then. Expansion follows a set across reruns by
 * its id. Has no SWT dependency; confined to the UI thread.
 */
final class ResultRows {
  /** Expansion of one set and the rows before each of its groups. */
  private static final class SetRows {
    boolean expanded = true;
    boolean running;
    final BitSet files = new BitSet();
    int[] before = new int[] {0};
  }

  private final Map<Long, SetRows> states = new HashMap<Long, SetRows>();
  private final List<SetRows> shown = new ArrayList<SetRows>();
  private ResultsInput input;
  private int count;

  /**
   * Reads the model again: drops the state of removed sets and recounts the rows.
   *
   * @param results the model, or null
   */
  void update(ResultsInput results) {
    input = results;
    shown.clear();
    count = 0;
    if (results == null) {
      states.clear();
      return;
    }
    Map<Long, SetRows> kept = new HashMap<Long, SetRows>();
    for (int s = 0; s < results.setCount(); s++) {
      long id = results.setId(s);
      SetRows rows = states.get(id);
      if (rows == null) {
        rows = new SetRows();
      }
      boolean running = results.running(s);
      if (rows.running && !running) {
        rows.files.clear();
      }
      rows.running = running;
      kept.put(id, rows);
      shown.add(rows);
      recount(results, s, rows);
      count += 1 + (rows.expanded ? rows.before[rows.before.length - 1] : 0);
    }
    states.clear();
    states.putAll(kept);
  }

  private static void recount(ResultsInput model, int set, SetRows rows) {
    int files = model.fileCount(set);
    if (rows.before.length != files + 1) {
      rows.before = new int[files + 1];
    }
    int total = 0;
    for (int f = 0; f < files; f++) {
      rows.before[f] = total;
      total += 1 + (rows.files.get(f) ? model.matchCount(set, f) : 0);
    }
    rows.before[files] = total;
  }

  /**
   * Counts the rows.
   *
   * @return the rows of every set, expanded parts included
   */
  int count() {
    return count;
  }

  /**
   * Returns the path of a row.
   *
   * @param row a row index
   * @return {@code {set}}, {@code {set, file}} or {@code {set, file, match}}; empty out of range
   */
  int[] path(int row) {
    int at = row;
    for (int s = 0; s < shown.size() && at >= 0; s++) {
      if (at == 0) {
        return new int[] {s};
      }
      at--;
      SetRows rows = shown.get(s);
      int inside = rows.expanded ? rows.before[rows.before.length - 1] : 0;
      if (at < inside) {
        int file = Arrays.binarySearch(rows.before, 0, rows.before.length - 1, at);
        if (file < 0) {
          file = -file - 2;
        }
        int offset = at - rows.before[file];
        return offset == 0 ? new int[] {s, file} : new int[] {s, file, offset - 1};
      }
      at -= inside;
    }
    return new int[0];
  }

  /**
   * Returns the row of a path, expanding nothing.
   *
   * @param path a set, group or match path
   * @return the row, or -1 when the path is hidden or out of range
   */
  int row(int[] path) {
    if (path.length == 0 || path[0] >= shown.size()) {
      return -1;
    }
    int row = 0;
    for (int s = 0; s < path[0]; s++) {
      SetRows rows = shown.get(s);
      row += 1 + (rows.expanded ? rows.before[rows.before.length - 1] : 0);
    }
    if (path.length == 1) {
      return row;
    }
    SetRows rows = shown.get(path[0]);
    if (!rows.expanded || path[1] >= rows.before.length - 1) {
      return -1;
    }
    row += 1 + rows.before[path[1]];
    if (path.length == 2) {
      return row;
    }
    return rows.files.get(path[1]) ? row + 1 + path[2] : -1;
  }

  /**
   * Reports whether a set or group row is expanded.
   *
   * @param path a set or group path
   * @return true when expanded; false for a match
   */
  boolean expanded(int[] path) {
    if (path.length == 0 || path.length == 3 || path[0] >= shown.size()) {
      return false;
    }
    SetRows rows = shown.get(path[0]);
    return path.length == 1 ? rows.expanded : rows.expanded && rows.files.get(path[1]);
  }

  /**
   * Expands or collapses a set or group; recounts the rows.
   *
   * @param path a set or group path
   * @param expand true to expand
   */
  void setExpanded(int[] path, boolean expand) {
    if (path.length == 0 || path.length == 3 || path[0] >= shown.size()) {
      return;
    }
    SetRows rows = shown.get(path[0]);
    if (path.length == 1) {
      rows.expanded = expand;
    } else {
      rows.files.set(path[1], expand);
      if (expand) {
        rows.expanded = true;
      }
    }
    update(input);
  }
}
