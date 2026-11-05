package io.github.ajdailey.qcaret.core.watch;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tells a {@link Listener} which watched files to check: every file every {@code pollMillis}, every
 * file on {@link #checkAll}, one file on {@link #check}, and the files a directory event names. One
 * {@code qcaret-watch-} thread runs while any file is watched; it ends when the last file is
 * unwatched or on {@link #close}, and a later {@link #watch} starts another.
 *
 * <p>Each directory holding a watched file is registered once with a {@link WatchService}, on the
 * thread, and its key is canceled when its last file is unwatched. A directory that cannot be
 * registered is polled only. The poll stays on regardless: a {@code WatchService} misses events on
 * network mounts, polls on macOS, and never reports a change to a symbolic link's target in another
 * directory.
 *
 * <p>The listener runs on the thread, outside the lock. An exception from it is passed to the
 * thread's uncaught-exception handler once until a call completes, and checking goes on. Lock
 * order: {@code lock} alone.
 */
public final class FileMonitor implements AutoCloseable {
  /** Receives the files to check on the monitor's thread. */
  public interface Listener {
    /**
     * Checks files.
     *
     * @param files watched files, each once, in the order they were first watched
     */
    @WorkerThread
    void check(List<Path> files);
  }

  /** Longest wait for a directory event before the thread looks for a request. */
  static final long SLICE_MILLIS = 100;

  private final Workers workers;
  private final long pollMillis;
  private final Listener listener;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Path, Integer> files = new HashMap<Path, Integer>();
  private final Set<Path> requested = new LinkedHashSet<Path>();
  private final Map<Path, WatchKey> keys = new HashMap<Path, WatchKey>();
  private final Set<Path> unregistered = new LinkedHashSet<Path>();
  private final List<Path> order = new ArrayList<Path>();
  private boolean all;
  private boolean running;
  private boolean closed;
  private WatchService service;
  private boolean serviceFailed;

  /**
   * Creates the monitor; no thread runs until a file is watched.
   *
   * @param workers starts the thread
   * @param pollMillis interval between checks of every file
   * @param listener receives the files to check
   */
  public FileMonitor(Workers workers, long pollMillis, Listener listener) {
    this.workers = workers;
    this.pollMillis = pollMillis;
    this.listener = listener;
  }

  /**
   * Watches a file; a file watched twice needs two {@link #unwatch} calls. Does no I/O.
   *
   * @param file the absolute path the user opened
   */
  public void watch(Path file) {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      Integer count = files.get(file);
      files.put(file, count == null ? 1 : count + 1);
      if (count == null) {
        order.add(file);
      }
      if (!running) {
        try {
          workers.start(
              Workers.Kind.WATCH,
              new Runnable() {
                @Override
                public void run() {
                  loop();
                }
              });
          running = true;
        } catch (RejectedExecutionException e) {
          // The workers are closed: nothing is checked from now on.
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops watching a file once for each {@link #watch}.
   *
   * @param file the path given to {@link #watch}
   */
  public void unwatch(Path file) {
    lock.lock();
    try {
      Integer count = files.get(file);
      if (count == null) {
        return;
      }
      if (count > 1) {
        files.put(file, count - 1);
      } else {
        files.remove(file);
        order.remove(file);
        requested.remove(file);
      }
    } finally {
      lock.unlock();
    }
  }

  /** Requests a check of every watched file, as on window activation. */
  public void checkAll() {
    lock.lock();
    try {
      all = true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Requests a check of one watched file.
   *
   * @param file the path given to {@link #watch}
   */
  public void check(Path file) {
    lock.lock();
    try {
      if (files.containsKey(file)) {
        requested.add(file);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Counts the directories registered with the {@code WatchService}.
   *
   * @return directories
   */
  public int watchedDirectories() {
    lock.lock();
    try {
      return keys.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reports whether the monitor's thread runs.
   *
   * @return true while a file is watched and the monitor is open
   */
  public boolean running() {
    lock.lock();
    try {
      return running;
    } finally {
      lock.unlock();
    }
  }

  /** Stops the thread within one slice; later calls to {@link #watch} do nothing. */
  @Override
  public void close() {
    lock.lock();
    try {
      closed = true;
      files.clear();
      order.clear();
      requested.clear();
    } finally {
      lock.unlock();
    }
  }

  @WorkerThread
  private void loop() {
    boolean failing = false;
    long nextPoll = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pollMillis);
    try {
      while (true) {
        List<Path> due = new ArrayList<Path>();
        WatchService watching;
        lock.lock();
        try {
          if (closed || files.isEmpty()) {
            stop();
            return;
          }
          syncKeys();
          boolean poll = System.nanoTime() - nextPoll >= 0;
          if (poll || all) {
            due.addAll(order);
            all = false;
            requested.clear();
            if (poll) {
              nextPoll = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pollMillis);
            }
          } else {
            due.addAll(requested);
            requested.clear();
          }
          watching = service;
        } finally {
          lock.unlock();
        }
        if (!due.isEmpty()) {
          try {
            listener.check(due);
            failing = false;
          } catch (Exception e) {
            if (!failing) {
              Thread self = Thread.currentThread();
              self.getUncaughtExceptionHandler().uncaughtException(self, e);
            }
            failing = true;
          }
        }
        waitForEvents(watching);
      }
    } catch (InterruptedException e) {
      lock.lock();
      try {
        stop();
      } finally {
        lock.unlock();
      }
      Thread.currentThread().interrupt();
    }
  }

  /** Waits one slice for directory events and queues the files they name. */
  private void waitForEvents(WatchService watching) throws InterruptedException {
    if (watching == null) {
      Thread.sleep(SLICE_MILLIS);
      return;
    }
    WatchKey key;
    try {
      key = watching.poll(SLICE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (ClosedWatchServiceException e) {
      return;
    }
    while (key != null) {
      List<WatchEvent<?>> events = key.pollEvents();
      key.reset();
      lock.lock();
      try {
        if (key.watchable() instanceof Path directory) {
          for (WatchEvent<?> event : events) {
            for (Path file : order) {
              Path name = file.getFileName();
              if (directory.equals(file.getParent())
                  && (event.kind() == StandardWatchEventKinds.OVERFLOW
                      || (name != null && name.equals(event.context())))) {
                requested.add(file);
              }
            }
          }
        }
      } finally {
        lock.unlock();
      }
      key = watching.poll();
    }
  }

  /** Registers the directories of watched files and cancels the others; called under the lock. */
  private void syncKeys() {
    Set<Path> directories = new LinkedHashSet<Path>();
    for (Path file : order) {
      Path directory = file.getParent();
      if (directory != null) {
        directories.add(directory);
      }
    }
    List<Path> gone = new ArrayList<Path>();
    for (Path directory : keys.keySet()) {
      if (!directories.contains(directory)) {
        gone.add(directory);
      }
    }
    for (Path directory : gone) {
      keys.remove(directory).cancel();
    }
    unregistered.retainAll(directories);
    if (serviceFailed) {
      return;
    }
    for (Path directory : directories) {
      if (keys.containsKey(directory) || unregistered.contains(directory)) {
        continue;
      }
      try {
        if (service == null) {
          service = directory.getFileSystem().newWatchService();
        }
        keys.put(
            directory,
            directory.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY));
      } catch (IOException | UnsupportedOperationException e) {
        if (service == null) {
          serviceFailed = true;
          return;
        }
        unregistered.add(directory);
      }
    }
  }

  /** Ends the thread's state and closes the service; called under the lock. */
  private void stop() {
    running = false;
    for (WatchKey key : keys.values()) {
      key.cancel();
    }
    keys.clear();
    unregistered.clear();
    if (service != null) {
      try {
        service.close();
      } catch (IOException e) {
        // Closing releases the native handle; nothing else to do.
      }
      service = null;
    }
  }
}
