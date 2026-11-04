package io.github.ajdailey.qcaret.app.view;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A {@link UiDispatcher} for tests: submissions queue until the test runs them on its own thread,
 * which acts as the UI thread. Timer delays are ignored; timer tasks queue in submission order.
 * Fakes call {@link #checkUiThread} in every view method, so a controller that reaches the view
 * from a worker fails the test instead of passing on the test thread.
 */
public final class ManualUiDispatcher implements UiDispatcher {
  private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
  private final Thread uiThread = Thread.currentThread();
  private volatile boolean disposed;

  @Override
  public void asyncExec(Runnable task) {
    if (!disposed && !queue.offer(task)) {
      throw new IllegalStateException("queue refused a task");
    }
  }

  @Override
  public void timerExec(int delayMillis, Runnable task) {
    asyncExec(task);
  }

  @Override
  public boolean isUiThread() {
    return Thread.currentThread() == uiThread;
  }

  /**
   * Fails when called off the thread that created this dispatcher, or while a task runs on another
   * thread.
   *
   * @throws IllegalStateException naming the calling thread
   */
  public void checkUiThread() {
    if (Thread.currentThread() != uiThread) {
      throw new IllegalStateException(
          "UI call on " + Thread.currentThread().getName() + ", not " + uiThread.getName());
    }
  }

  /** Drops queued and later submissions, as a disposed Display does. */
  public void dispose() {
    disposed = true;
    queue.clear();
  }

  /**
   * Runs the tasks queued now, and any they queue, until the queue is empty.
   *
   * @return the number run
   */
  public int runPending() {
    int count = 0;
    Runnable task = queue.poll();
    while (task != null) {
      task.run();
      count++;
      task = queue.poll();
    }
    return count;
  }

  /**
   * Runs tasks as they arrive until the condition holds.
   *
   * @param condition checked before each wait
   * @param bound the longest total wait
   * @return true when the condition held within the bound
   * @throws InterruptedException when interrupted while waiting
   */
  public boolean runUntil(BooleanSupplier condition, Duration bound) throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (!condition.getAsBoolean()) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      Runnable task = queue.poll(left, TimeUnit.NANOSECONDS);
      if (task != null) {
        task.run();
      }
    }
    return true;
  }
}
