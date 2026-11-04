package io.github.ajdailey.qcaret.core.concurrent;

import io.github.ajdailey.qcaret.core.util.Checks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns every thread QCaret starts: a fixed pool of platform threads for CPU-bound work ({@code
 * qcaret-cpu-N}) and virtual threads for blocking work, named by {@link Kind}. Nothing else creates
 * a thread or an executor. The launcher creates one instance and closes it at shutdown.
 *
 * <p>Every task runs inside a wrapper that passes an exception it ends with to the failure handler;
 * the same handler is each thread's uncaught exception handler. No task returns a {@code Future},
 * so no failure is lost in a discarded one.
 *
 * <p>Pool threads are daemon threads: a missed {@link #close} cannot keep the JVM alive. Tests
 * detect a leak through {@link #liveThreads}.
 */
public final class Workers implements AutoCloseable {
  /** Default bound for {@link #close()}. */
  public static final Duration CLOSE_BOUND = Duration.ofSeconds(5);

  private static final int CPU_QUEUE = 4096;

  /** Virtual thread kinds, each with its own name prefix. */
  public enum Kind {
    /** File open, decode, save, reload and recovery writes. */
    IO("qcaret-io-"),
    /** Directory walks and search coordinators. */
    SEARCH("qcaret-search-"),
    /** File watching and polls. */
    WATCH("qcaret-watch-"),
    /** Single-instance listener and connections. */
    IPC("qcaret-ipc-"),
    /** Configuration saves and shutdown. */
    LIFECYCLE("qcaret-lifecycle-"),
    /** The event-loop watchdog. */
    WATCHDOG("qcaret-watchdog-");

    private final String prefix;

    Kind(String prefix) {
      this.prefix = prefix;
    }

    /**
     * Returns the thread name prefix; a counter follows it.
     *
     * @return for example {@code qcaret-io-}
     */
    public String prefix() {
      return prefix;
    }
  }

  private final Thread.UncaughtExceptionHandler failures;
  private final ThreadPoolExecutor cpu;
  private final ThreadFactory[] factories;
  private final Set<Thread> live = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates the pools.
   *
   * @param cpuThreads CPU pool size, at least 1
   * @param failures receives every exception a task ends with
   */
  public Workers(int cpuThreads, Thread.UncaughtExceptionHandler failures) {
    Checks.atLeast(cpuThreads, 1, "cpuThreads");
    this.failures = failures;
    ThreadFactory cpuFactory =
        Thread.ofPlatform()
            .name("qcaret-cpu-", 1)
            .daemon(true)
            .uncaughtExceptionHandler(failures)
            .factory();
    this.cpu =
        new ThreadPoolExecutor(
            cpuThreads,
            cpuThreads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(CPU_QUEUE),
            cpuFactory);
    Kind[] kinds = Kind.values();
    this.factories = new ThreadFactory[kinds.length];
    for (Kind kind : kinds) {
      factories[kind.ordinal()] =
          Thread.ofVirtual().name(kind.prefix(), 1).uncaughtExceptionHandler(failures).factory();
    }
  }

  /**
   * Runs a CPU-bound task on the pool.
   *
   * @param task the task
   * @throws RejectedExecutionException after {@link #close} or when the queue is full
   */
  public void executeCpu(Runnable task) {
    if (closed.get()) {
      throw new RejectedExecutionException("Workers are closed");
    }
    cpu.execute(guard(task));
  }

  /**
   * Starts a blocking task on a new virtual thread. The thread joins the live set before {@code
   * closed} is checked a second time, so {@link #close} either waits for it or the start is
   * rejected.
   *
   * @param kind names the thread
   * @param task the task
   * @return the started thread
   * @throws RejectedExecutionException after {@link #close}
   */
  public Thread start(Kind kind, Runnable task) {
    if (closed.get()) {
      throw new RejectedExecutionException("Workers are closed");
    }
    final Runnable guarded = guard(task);
    final Thread[] self = new Thread[1];
    Thread thread =
        factories[kind.ordinal()].newThread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  guarded.run();
                } finally {
                  live.remove(self[0]);
                }
              }
            });
    self[0] = thread;
    live.add(thread);
    if (closed.get()) {
      live.remove(thread);
      throw new RejectedExecutionException("Workers are closed");
    }
    thread.start();
    return thread;
  }

  /**
   * Starts a blocking task as {@link #start} does, unless the workers are closed.
   *
   * @param kind names the thread
   * @param task the task
   * @return false, starting nothing, after {@link #close}
   */
  public boolean tryStart(Kind kind, Runnable task) {
    try {
      start(kind, task);
      return true;
    } catch (RejectedExecutionException e) {
      return false;
    }
  }

  /**
   * Returns the CPU pool size.
   *
   * @return the size given to the constructor
   */
  public int cpuThreads() {
    return cpu.getCorePoolSize();
  }

  /**
   * Reports whether {@link #close} has begun.
   *
   * @return true once closing
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Counts threads that have not ended: started virtual threads plus pool threads.
   *
   * @return the count; 0 after a successful close
   */
  public int liveThreads() {
    return live.size() + cpu.getPoolSize();
  }

  /**
   * Names the threads that have not ended, for a log line.
   *
   * @return the names, comma-separated, pool threads as {@code qcaret-cpu-*} with their count
   */
  public String liveThreadNames() {
    List<String> names = new ArrayList<String>();
    for (Thread thread : live) {
      names.add(thread.getName());
    }
    Collections.sort(names);
    int pool = cpu.getPoolSize();
    if (pool > 0) {
      names.add("qcaret-cpu-* x" + pool);
    }
    return String.join(", ", names);
  }

  /**
   * Creates the JVM shutdown hook's thread, {@code qcaret-shutdown}, not started; the caller
   * registers it with {@code Runtime.addShutdownHook}. It does not join the live set, so {@link
   * #close} neither waits for nor interrupts it.
   *
   * @param task what the hook runs
   * @return the unstarted platform thread
   */
  public Thread shutdownHook(Runnable task) {
    return Thread.ofPlatform()
        .name("qcaret-shutdown")
        .uncaughtExceptionHandler(failures)
        .unstarted(guard(task));
  }

  /** Closes with {@link #CLOSE_BOUND}. */
  @Override
  public void close() {
    close(CLOSE_BOUND);
  }

  /**
   * Stops accepting tasks, waits up to {@code bound} for running ones, then interrupts what is left
   * ({@code shutdownNow} for the pool). A virtual thread calling this method does not wait for
   * itself. The live set is read again until it holds no other thread, so a thread a racing {@link
   * #start} added is waited for too.
   *
   * @param bound the longest wait
   * @return true when every thread except the caller ended within the bound
   */
  public boolean close(Duration bound) {
    closed.set(true);
    cpu.shutdown();
    long deadline = System.nanoTime() + bound.toNanos();
    try {
      if (!cpu.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
        cpu.shutdownNow();
      }
      Thread current = Thread.currentThread();
      List<Thread> threads = others(current);
      while (!threads.isEmpty() && remaining(deadline) > 0) {
        for (Thread thread : threads) {
          if (awaitStarted(thread, deadline)
              && !thread.join(Duration.ofNanos(remaining(deadline)))) {
            thread.interrupt();
          }
        }
        threads = others(current);
      }
      for (Thread thread : threads) {
        thread.interrupt();
      }
    } catch (InterruptedException e) {
      cpu.shutdownNow();
      Thread.currentThread().interrupt();
      return false;
    }
    return cpu.isTerminated() && others(Thread.currentThread()).isEmpty();
  }

  /** The live threads except {@code current}. */
  private List<Thread> others(Thread current) {
    List<Thread> threads = new ArrayList<Thread>(live);
    threads.remove(current);
    return threads;
  }

  /**
   * Waits, bounded, while a thread {@link #start} added to the live set is not yet started: its
   * starter either starts it or, having seen {@code closed}, removes it again.
   *
   * @return false when the thread did not start, so there is nothing to join
   */
  private boolean awaitStarted(Thread thread, long deadline) throws InterruptedException {
    while (thread.getState() == Thread.State.NEW) {
      if (!live.contains(thread) || remaining(deadline) == 0) {
        return thread.getState() != Thread.State.NEW;
      }
      Thread.sleep(1);
    }
    return true;
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  private Runnable guard(final Runnable task) {
    return new Runnable() {
      @Override
      public void run() {
        try {
          task.run();
        } catch (Exception e) {
          failures.uncaughtException(Thread.currentThread(), e);
        }
      }
    };
  }
}
