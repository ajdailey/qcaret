package io.github.ajdailey.qcaret.ui.swt.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import org.junit.jupiter.api.Test;

/**
 * Rows of sets, groups and matches: paths both ways through expansion and collapse, expansion kept
 * by set id when sets are added, and groups collapsed when a search ends.
 */
class ResultRowsTest {
  /** Sets with ids and per-group match counts; running sets listed by index. */
  private static final class Model implements ResultsInput {
    long[] ids;
    int[][] matches;
    boolean[] running;

    Model(long[] ids, int[][] matches) {
      this.ids = ids;
      this.matches = matches;
      this.running = new boolean[ids.length];
    }

    @Override
    public int setCount() {
      return ids.length;
    }

    @Override
    public long setId(int set) {
      return ids[set];
    }

    @Override
    public ResultLabel set(int set) {
      return ResultLabel.plain("set " + set, false);
    }

    @Override
    public int fileCount(int set) {
      return matches[set].length;
    }

    @Override
    public ResultLabel file(int set, int file) {
      return ResultLabel.plain("file " + file, false);
    }

    @Override
    public int matchCount(int set, int file) {
      return matches[set][file];
    }

    @Override
    public ResultLabel match(int set, int file, int match) {
      return ResultLabel.plain("match " + match, false);
    }

    @Override
    public void open(int set, int file, int match) {
      // Not opened here.
    }

    @Override
    public void expanded(int set, int file) {
      // Not checked here.
    }

    @Override
    public void rerun(int set) {
      // Not rerun here.
    }

    @Override
    public void stop(int set) {
      // Not stopped here.
    }

    @Override
    public void togglePin(int set) {
      // Not pinned here.
    }

    @Override
    public boolean pinned(int set) {
      return false;
    }

    @Override
    public boolean running(int set) {
      return running[set];
    }

    @Override
    public void clear(int set) {
      // Not cleared here.
    }

    @Override
    public void clearAll() {
      // Not cleared here.
    }

    @Override
    public void hide() {
      // Not hidden here.
    }
  }

  @Test
  void pathsAndRowsFollowExpansion() {
    Model model = new Model(new long[] {2, 1}, new int[][] {{3, 100_000}, {1}});
    ResultRows rows = new ResultRows();
    rows.update(model);
    assertEquals(5, rows.count());
    assertArrayEquals(new int[] {0, 1}, rows.path(2));
    rows.setExpanded(new int[] {0, 1}, true);
    assertEquals(100_005, rows.count());
    assertArrayEquals(new int[] {0, 1, 99_999}, rows.path(100_002));
    assertArrayEquals(new int[] {1}, rows.path(100_003));
    assertEquals(100_002, rows.row(new int[] {0, 1, 99_999}));
    assertEquals(-1, rows.row(new int[] {0, 0, 0}));
    rows.setExpanded(new int[] {0}, false);
    assertEquals(3, rows.count());
    assertArrayEquals(new int[] {1, 0}, rows.path(2));
    assertEquals(0, rows.path(3).length);
    assertFalse(rows.expanded(new int[] {0}));
    assertFalse(rows.expanded(new int[] {0, 1}));
    rows.setExpanded(new int[] {0}, true);
    assertEquals(100_005, rows.count());
  }

  @Test
  void expansionFollowsTheSetIdAndGroupsCollapseWhenASearchEnds() {
    Model model = new Model(new long[] {1}, new int[][] {{2}});
    model.running[0] = true;
    ResultRows rows = new ResultRows();
    rows.update(model);
    rows.setExpanded(new int[] {0, 0}, true);
    assertEquals(4, rows.count());
    model.ids = new long[] {2, 1};
    model.matches = new int[][] {{1}, {2}};
    model.running = new boolean[] {false, true};
    rows.update(model);
    assertTrue(rows.expanded(new int[] {1, 0}));
    assertEquals(6, rows.count());
    model.running = new boolean[] {false, false};
    rows.update(model);
    assertFalse(rows.expanded(new int[] {1, 0}));
    assertEquals(4, rows.count());
  }
}
