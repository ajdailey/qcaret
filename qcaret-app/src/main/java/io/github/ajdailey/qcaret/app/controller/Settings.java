package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running settings: the one copy of {@link Preferences} every controller reads, and their route
 * to {@link ConfigStore}. Confined to the UI thread apart from {@link #flush}.
 *
 * <p>{@link #set} replaces the settings, tells the listeners and queues the change for a {@code
 * qcaret-lifecycle-} worker. Workers drain the queue in order under {@link #saveLock}, one save
 * from the first change's old settings to the last change's new ones, so the store writes exactly
 * the keys this process changed. {@link #refresh} takes up what another process saved.
 */
public final class Settings {
  private static final Logger LOG = LoggerFactory.getLogger(Settings.class);

  /** Called on the UI thread after the settings changed. */
  public interface Listener {
    /**
     * The settings changed.
     *
     * @param before the old settings
     * @param after the new settings
     */
    void changed(Preferences before, Preferences after);
  }

  /** One queued change. */
  private record Change(Preferences before, Preferences after, Consumer<Boolean> saved) {}

  private final ConfigStore config;
  private final Workers workers;
  private final UiDispatcher ui;
  private final List<Listener> listeners = new ArrayList<Listener>();
  private final Queue<Change> pending = new ConcurrentLinkedQueue<Change>();

  /** Orders the writes of different workers; the UI thread never takes it. */
  private final ReentrantLock saveLock = new ReentrantLock();

  /** The last save failed, so the file lags these settings; guarded by {@link #saveLock}. */
  private boolean behind;

  private Preferences current;

  Settings(ConfigStore config, Workers workers, UiDispatcher ui, Preferences initial) {
    this.config = config;
    this.workers = workers;
    this.ui = ui;
    this.current = initial;
  }

  /**
   * Returns the settings.
   *
   * @return the running settings
   */
  public Preferences current() {
    return current;
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread after each change
   */
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /**
   * Replaces the settings; equal settings do nothing.
   *
   * @param next the new settings
   */
  public void set(Preferences next) {
    set(next, null);
  }

  /**
   * Replaces the settings; equal settings do nothing.
   *
   * @param next the new settings
   * @param saved receives on the UI thread whether the file took them, or null
   */
  void set(Preferences next, Consumer<Boolean> saved) {
    if (next.equals(current)) {
      if (saved != null) {
        saved.accept(true);
      }
      return;
    }
    record(next, saved);
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                flush();
              }
            });
    if (!started) {
      LOG.warn("Settings not saved: workers are closed");
    }
  }

  /**
   * Replaces the settings and queues the change without starting a worker; the caller runs {@link
   * #flush}.
   *
   * @param next the new settings
   */
  void record(Preferences next) {
    if (!next.equals(current)) {
      record(next, null);
    }
  }

  private void record(Preferences next, Consumer<Boolean> saved) {
    Preferences before = current;
    current = next;
    pending.add(new Change(before, next, saved));
    fire(before, next);
  }

  private void fire(Preferences before, Preferences after) {
    for (Listener listener : new ArrayList<Listener>(listeners)) {
      listener.changed(before, after);
    }
  }

  /** Saves every queued change. Blocking. */
  @WorkerThread
  void flush() {
    saveLock.lock();
    try {
      drain();
    } finally {
      saveLock.unlock();
    }
  }

  @WorkerThread
  private void drain() {
    List<Change> changes = new ArrayList<Change>();
    Change change = pending.poll();
    while (change != null) {
      changes.add(change);
      change = pending.poll();
    }
    if (changes.isEmpty()) {
      return;
    }
    Preferences first = changes.get(0).before();
    final boolean ok = config.save(first, changes.get(changes.size() - 1).after());
    behind = !ok;
    for (final Change done : changes) {
      if (done.saved() != null) {
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                done.saved().accept(ok);
              }
            });
      }
    }
  }

  /**
   * Saves what is queued, reads the file on a worker and takes up what another process changed; the
   * listeners hear of it like any change. A failed save or a read-only directory keeps the running
   * settings, which the file lags.
   *
   * @param done runs on the UI thread at the end, also when the workers are closed
   */
  void refresh(final Runnable done) {
    final Preferences requested = current;
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final Preferences read = read();
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        adopt(requested, read);
                        done.run();
                      }
                    });
              }
            });
    if (!started) {
      done.run();
    }
  }

  /** Returns the file's settings once the queue is written, or null when they cannot be used. */
  @WorkerThread
  private Preferences read() {
    saveLock.lock();
    try {
      drain();
      Preferences read = config.load();
      return behind || config.saveDisabled() ? null : read;
    } finally {
      saveLock.unlock();
    }
  }

  private void adopt(Preferences requested, Preferences read) {
    if (read == null) {
      return;
    }
    Preferences before = current;
    Preferences next = before == requested ? read : ConfigStore.merge(requested, read, before);
    if (!next.equals(before)) {
      current = next;
      fire(before, next);
    }
  }
}
