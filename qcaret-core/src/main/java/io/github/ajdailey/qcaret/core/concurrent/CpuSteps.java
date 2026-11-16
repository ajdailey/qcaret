package io.github.ajdailey.qcaret.core.concurrent;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the CPU-bound steps of a blocking task on the {@code qcaret-bulk-} pool. On a virtual thread
 * that {@link Workers#start} started, {@link #run} submits the step to that {@code Workers}' bulk
 * pool and waits for it, so the carrier is free while the step computes; on any other thread it
 * runs the step inline. A step must not block on I/O: the caller reads or writes between steps.
 */
public final class CpuSteps {
  /** How often a waiter checks its token while its step is queued. */
  private static final long POLL_MILLIS = 50;

  /** The {@code Workers} that started the current virtual thread; unset elsewhere. */
  static final ThreadLocal<Workers> OWNER = new ThreadLocal<Workers>();

  private CpuSteps() {}

  /**
   * A CPU-bound step.
   *
   * @param <T> its result
   */
  public interface Step<T> {
    /**
     * Computes the result.
     *
     * @return the result
     * @throws IOException passed to the caller of {@link CpuSteps#run}
     */
    T run() throws IOException;
  }

  /**
   * Runs a step on the bulk pool and returns its result, or runs it inline off a virtual {@code
   * Workers} thread. A cancel while the step is queued ends the wait; a running step is waited for,
   * so it never outlives the caller's resources, and checks the token itself.
   *
   * @param <T> the step's result
   * @param token canceled to stop waiting for a queued step, or null
   * @param step the step
   * @return the step's result
   * @throws IOException what the step threw
   * @throws CancellationException when canceled or interrupted before the step started, or when the
   *     pool is closed or full
   */
  public static <T> T run(CancellationToken token, Step<T> step) throws IOException {
    Workers owner = OWNER.get();
    if (owner == null || !Thread.currentThread().isVirtual()) {
      return step.run();
    }
    Handoff<T> handoff = new Handoff<T>(step);
    try {
      owner.executeBulk(handoff);
    } catch (RejectedExecutionException e) {
      throw new CancellationException("the bulk pool refused the step");
    }
    return handoff.await(token);
  }

  /**
   * A CPU-bound step that throws no checked exception.
   *
   * @param <T> its result
   */
  public interface Compute<T> {
    /**
     * Computes the result.
     *
     * @return the result
     */
    T run();
  }

  /**
   * Runs a step as {@link #run} does.
   *
   * @param <T> the step's result
   * @param token canceled to stop waiting for a queued step, or null
   * @param step the step
   * @return the step's result
   * @throws CancellationException as {@link #run} does
   */
  public static <T> T compute(CancellationToken token, final Compute<T> step) {
    try {
      return run(
          token,
          new Step<T>() {
            @Override
            public T run() {
              return step.run();
            }
          });
    } catch (IOException e) {
      throw new IllegalStateException("A compute step threw an IOException", e);
    }
  }

  /** A step queued on the bulk pool and the result its waiter reads. */
  static final class Handoff<T> implements Runnable {
    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int DROPPED = 2;

    private final Step<T> step;
    private final AtomicInteger state = new AtomicInteger(QUEUED);
    private final CountDownLatch done = new CountDownLatch(1);
    private T result;
    private Exception failure;
    private Error error;

    Handoff(Step<T> step) {
      this.step = step;
    }

    @Override
    public void run() {
      if (!state.compareAndSet(QUEUED, RUNNING)) {
        return;
      }
      try {
        result = step.run();
      } catch (Exception e) {
        failure = e;
      } catch (Error e) {
        error = e;
      } finally {
        done.countDown();
      }
    }

    /** Drops the step when it has not started; its waiter then throws. */
    void drop() {
      if (state.compareAndSet(QUEUED, DROPPED)) {
        done.countDown();
      }
    }

    T await(CancellationToken token) throws IOException {
      boolean interrupted = false;
      try {
        while (true) {
          try {
            if (done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
              break;
            }
            if (token != null && token.isCanceled()) {
              drop();
            }
          } catch (InterruptedException e) {
            interrupted = true;
            drop();
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (state.get() == DROPPED) {
        throw new CancellationException("the step did not run");
      }
      if (error != null) {
        throw error;
      }
      if (failure instanceof IOException e) {
        throw e;
      }
      if (failure instanceof RuntimeException e) {
        throw e;
      }
      if (failure != null) {
        throw new IllegalStateException(failure);
      }
      return result;
    }
  }
}
