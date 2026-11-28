package io.github.ajdailey.qcaret.app.view;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A {@link UiDispatcher} over another that times each task it runs between {@link #start} and
 * {@link #stop}, and a clock for a macro run's turn budget ({@link #clock}) that splits a task at
 * each read. A task that reads the clock is a turn; each stretch between reads, and from the last
 * read to the task's end, is one step or the turn's overhead. Confined to the UI thread except
 * {@link #asyncExec}.
 */
public final class PlaybackTimer implements UiDispatcher {
  /** The turn budget, {@code MacroPlayer.TURN_NANOS}. */
  public static final long BUDGET_NANOS = 8_000_000L;

  private final UiDispatcher delegate;
  private final Map<Runnable, Runnable> timers = new IdentityHashMap<Runnable, Runnable>();
  private final Samples turns = new Samples();
  private final Samples turnSteps = new Samples();
  private final Samples others = new Samples();
  private boolean recording;
  private boolean inTask;
  private long mark;
  private long longestStep;
  private int reads;

  /**
   * Creates the timer.
   *
   * @param delegate runs the tasks
   */
  public PlaybackTimer(UiDispatcher delegate) {
    this.delegate = delegate;
  }

  /** Clears the samples and times the tasks that run from now. */
  public void start() {
    turns.clear();
    turnSteps.clear();
    others.clear();
    recording = true;
  }

  /** Stops timing. */
  public void stop() {
    recording = false;
  }

  /**
   * Returns the clock to give a macro run: {@link System#nanoTime}, marking a step boundary.
   *
   * @return the clock
   */
  public LongSupplier clock() {
    return new LongSupplier() {
      @Override
      public long getAsLong() {
        long now = System.nanoTime();
        if (inTask) {
          longestStep = Math.max(longestStep, now - mark);
          mark = now;
          reads++;
        }
        return now;
      }
    };
  }

  @Override
  public void asyncExec(Runnable task) {
    delegate.asyncExec(timed(task));
  }

  @Override
  public void timerExec(int delayMillis, Runnable task) {
    Runnable wrapper = timers.get(task);
    if (wrapper == null) {
      wrapper = timerTask(task);
      timers.put(task, wrapper);
    }
    delegate.timerExec(delayMillis, wrapper);
  }

  @Override
  public void cancelTimer(Runnable task) {
    Runnable wrapper = timers.remove(task);
    if (wrapper != null) {
      delegate.cancelTimer(wrapper);
    }
  }

  @Override
  public boolean isUiThread() {
    return delegate.isUiThread();
  }

  private Runnable timerTask(final Runnable task) {
    return new Runnable() {
      @Override
      public void run() {
        timers.remove(task);
        runTimed(task);
      }
    };
  }

  private Runnable timed(final Runnable task) {
    return new Runnable() {
      @Override
      public void run() {
        runTimed(task);
      }
    };
  }

  private void runTimed(Runnable task) {
    if (!recording || inTask) {
      task.run();
      return;
    }
    inTask = true;
    reads = 0;
    longestStep = 0;
    long start = System.nanoTime();
    mark = start;
    try {
      task.run();
    } finally {
      long end = System.nanoTime();
      inTask = false;
      if (reads > 0) {
        turns.add(end - start);
        turnSteps.add(Math.max(longestStep, end - mark));
      } else {
        others.add(end - start);
      }
    }
  }

  /**
   * Returns the samples since {@link #start}.
   *
   * @return the summary
   */
  public Summary summary() {
    int overBudget = 0;
    int overNotOneStep = 0;
    for (int i = 0; i < turns.size; i++) {
      if (turns.values[i] > BUDGET_NANOS) {
        overBudget++;
        if (turns.values[i] - turnSteps.values[i] > BUDGET_NANOS) {
          overNotOneStep++;
        }
      }
    }
    return new Summary(
        turns.size,
        turns.percentile(50),
        turns.percentile(99),
        turns.max(),
        overBudget,
        overNotOneStep,
        turnSteps.max(),
        turns.sum(),
        others.size,
        others.percentile(99),
        others.max(),
        others.sum());
  }

  /**
   * Turn and other task times in nanoseconds.
   *
   * @param turns tasks that ran macro steps
   * @param turnP50 median turn
   * @param turnP99 99th percentile turn
   * @param turnMax longest turn
   * @param overBudget turns over {@link #BUDGET_NANOS}
   * @param overNotOneStep turns over the budget by more than their longest step
   * @param longestStep longest step or turn overhead
   * @param turnSum all turns
   * @param others other tasks: worker results, rewraps, timers
   * @param otherP99 99th percentile other task
   * @param otherMax longest other task
   * @param otherSum all other tasks
   */
  public record Summary(
      int turns,
      long turnP50,
      long turnP99,
      long turnMax,
      int overBudget,
      int overNotOneStep,
      long longestStep,
      long turnSum,
      int others,
      long otherP99,
      long otherMax,
      long otherSum) {
    /** Returns the summary as one line, milliseconds. */
    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "turns=%d p50=%.3f p99=%.3f max=%.3f over8=%d over8NotOneStep=%d longestStep=%.3f"
              + " turnSum=%.1f others=%d otherP99=%.3f otherMax=%.3f otherSum=%.1f",
          turns,
          turnP50 / 1e6,
          turnP99 / 1e6,
          turnMax / 1e6,
          overBudget,
          overNotOneStep,
          longestStep / 1e6,
          turnSum / 1e6,
          others,
          otherP99 / 1e6,
          otherMax / 1e6,
          otherSum / 1e6);
    }
  }

  /** A growable array of samples. */
  private static final class Samples {
    long[] values = new long[1024];
    int size;

    void add(long value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = value;
    }

    void clear() {
      size = 0;
    }

    long percentile(int p) {
      if (size == 0) {
        return 0;
      }
      long[] sorted = Arrays.copyOf(values, size);
      Arrays.sort(sorted);
      return sorted[Math.min(size - 1, (int) ((long) size * p / 100))];
    }

    long max() {
      long max = 0;
      for (int i = 0; i < size; i++) {
        max = Math.max(max, values[i]);
      }
      return max;
    }

    long sum() {
      long sum = 0;
      for (int i = 0; i < size; i++) {
        sum += values[i];
      }
      return sum;
    }
  }
}
