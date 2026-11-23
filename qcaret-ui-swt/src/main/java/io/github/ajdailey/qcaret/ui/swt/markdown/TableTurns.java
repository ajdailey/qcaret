package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import java.util.ArrayList;
import java.util.List;

/**
 * Paces a pane's table work in UI turns and holds the tables that wait for the view. A turn runs
 * {@link Work#step} with a deadline {@value #TURN_MILLIS} ms after its start less 1 ms, and another
 * turn follows while the queue holds a table. A table more than one view height below the view, or
 * above it and never laid out, waits until {@link #setView} finds it near. {@link #restart} and
 * {@link #cancel} drop a scheduled turn. Call on the UI thread.
 */
final class TableTurns {
  /** The longest a turn aims to take. */
  static final int TURN_MILLIS = 8;

  /** The time before {@link #TURN_MILLIS} by which a turn plans to end its steps. */
  private static final long SLACK_NANOS = 1_000_000L;

  /** The table work a turn runs. */
  interface Work {
    /** Reports whether a turn may run: content is set and the widget is not disposed. */
    boolean live();

    /** Runs queued steps until the deadline, a {@link System#nanoTime} value. */
    void step(long deadline);

    /** Returns a table of the content. */
    MarkdownTable table(int index);

    /** Reports whether the map shows a table's rows as laid out. */
    boolean laidOut(int index);
  }

  private final UiDispatcher ui;
  private final Work work;
  private final List<Integer> queue = new ArrayList<Integer>();
  private final List<Integer> waiting = new ArrayList<Integer>();
  private int viewTop;
  private int viewBottom;
  private long generation;
  private int turns;
  private long longestTurn;

  TableTurns(UiDispatcher ui, Work work) {
    this.ui = ui;
    this.work = work;
  }

  /**
   * Reports whether a step that took {@code last} nanoseconds fits twice before the deadline.
   *
   * @param last the last step's time in nanoseconds
   * @param deadline a {@link System#nanoTime} value
   * @return true while another step may start
   */
  static boolean fits(long last, long deadline) {
    return System.nanoTime() + 2 * last < deadline;
  }

  /**
   * Empties the queue and the waiting tables, drops a scheduled turn and takes the lines in view.
   *
   * @param topLine the first rendered line in view
   * @param bottomLine the last rendered line in view
   */
  void restart(int topLine, int bottomLine) {
    generation++;
    viewTop = topLine;
    viewBottom = bottomLine;
    queue.clear();
    waiting.clear();
  }

  /**
   * Queues tables in order and schedules a turn when the queue holds one.
   *
   * @param tables table indexes, the tables in view first
   */
  void queue(List<Integer> tables) {
    queue.addAll(tables);
    if (!queue.isEmpty()) {
      schedule();
    }
  }

  /**
   * Takes the rendered lines in view and queues each waiting table near them.
   *
   * @param topLine the first rendered line in view
   * @param bottomLine the last rendered line in view
   */
  void setView(int topLine, int bottomLine) {
    viewTop = topLine;
    viewBottom = bottomLine;
    boolean idle = queue.isEmpty();
    for (int i = 0; i < waiting.size(); ) {
      if (near(waiting.get(i))) {
        queue.add(waiting.remove(i));
      } else {
        i++;
      }
    }
    if (idle && !queue.isEmpty()) {
      schedule();
    }
  }

  /**
   * Reports whether a table is near the lines in view: it reaches them, starts within one view
   * height below them, or ends within one view height above them with rows already laid out, as a
   * width change leaves it. A replace lays out each unmeasured line between the top line and the
   * rows it shows; above the view that is every line down to the top line again for each replace
   * (3-12 ms after a jump, GTK 3), so a table above the view never laid out waits until it scrolls
   * in.
   *
   * @param index the table's index in the render
   * @return true when the table is near
   */
  boolean near(int index) {
    MarkdownTable table = work.table(index);
    int span = viewBottom - viewTop + 1;
    int above = work.laidOut(index) ? span : 0;
    return table.firstLine() <= viewBottom + span
        && table.firstLine() + table.rowCount() > viewTop - above;
  }

  /** Returns the table at the head of the queue; call while {@link #busy}. */
  int head() {
    return queue.get(0);
  }

  /** Removes the table at the head of the queue. */
  void done() {
    queue.remove(0);
  }

  /** Moves the table at the head of the queue to the waiting tables. */
  void park() {
    waiting.add(queue.remove(0));
  }

  /**
   * Reports whether a table waits for the view to come near before its layout.
   *
   * @param table the table's index in the render
   * @return true while it waits
   */
  boolean waiting(int table) {
    return waiting.contains(table);
  }

  /** Reports whether measuring or a layout is still to run; a waiting table is not. */
  boolean busy() {
    return !queue.isEmpty();
  }

  /** Stops the work. */
  void cancel() {
    generation++;
    queue.clear();
    waiting.clear();
  }

  /** Counts the UI turns, for tests. */
  int turns() {
    return turns;
  }

  /** Returns the longest turn so far in nanoseconds, for measurements. */
  long longestTurn() {
    return longestTurn;
  }

  private void schedule() {
    final long scheduled = generation;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (scheduled == generation && work.live()) {
              turn();
            }
          }
        });
  }

  private void turn() {
    turns++;
    long start = System.nanoTime();
    try {
      work.step(start + TURN_MILLIS * 1_000_000L - SLACK_NANOS);
    } finally {
      longestTurn = Math.max(longestTurn, System.nanoTime() - start);
    }
    if (!queue.isEmpty()) {
      schedule();
    }
  }
}
