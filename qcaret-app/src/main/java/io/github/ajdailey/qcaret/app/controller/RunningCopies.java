package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Save a Copy As writes running in every window, closed tabs and closed windows included, the
 * one quit step waiting for them, and the window a copy's result shows in. After {@link #BOUND} a
 * modal prompt in the active window names each copy still running. Quit runs the step, logs the
 * copies, and they stop counting; Wait waits another bound. A copy ending while the prompt shows
 * closes it and runs the step as before. Confined to the UI thread.
 */
final class RunningCopies {
  /** The longest a quit waits for running copies. */
  static final Duration BOUND = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(RunningCopies.class);

  private final WindowSet set;
  private final UiDispatcher ui;
  private final List<Path> running = new ArrayList<Path>();
  private final List<Path> abandoned = new ArrayList<Path>();
  private Runnable done;
  private Runnable failed;
  private Runnable prompt;
  private final Runnable expired =
      new Runnable() {
        @Override
        public void run() {
          expire();
        }
      };

  /**
   * Creates the list with no copy.
   *
   * @param set the windows a result and the bound's prompt show in
   * @param ui runs the bound's timer
   */
  RunningCopies(WindowSet set, UiDispatcher ui) {
    this.set = set;
    this.ui = ui;
  }

  /** Reports whether a copy runs that a quit waits for. */
  boolean any() {
    return !running.isEmpty();
  }

  /**
   * A copy started.
   *
   * @param target the file it writes
   */
  void started(Path target) {
    running.add(target);
  }

  /**
   * A copy ended. A failure runs the waiting step's {@code failed}; the last copy to end runs its
   * {@code done}. A cancel counts as an end, not a failure. A copy left running by a Quit answer
   * runs neither.
   *
   * @param target the file it wrote
   * @param failure true when the write failed
   */
  void ended(Path target, boolean failure) {
    if (abandoned.remove(target) || !running.remove(target)) {
      return;
    }
    run(failure ? failed : running.isEmpty() ? done : null);
  }

  /**
   * Runs {@code done} now when no copy runs, otherwise once the last one ends or Quit is answered
   * at the prompt shown after {@link #BOUND}; a copy failing first runs {@code failed} instead. A
   * later call replaces the waiting step and restarts the bound.
   *
   * @param done the step after the copies
   * @param failed the step after a failed copy
   */
  void await(Runnable done, Runnable failed) {
    if (running.isEmpty()) {
      done.run();
      return;
    }
    this.done = done;
    this.failed = failed;
    ui.cancelTimer(expired);
    ui.timerExec((int) BOUND.toMillis(), expired);
  }

  /**
   * Returns the window a copy's result shows in: {@code asking} while its window is open, else the
   * active window, else {@code asking}.
   *
   * @param asking the window that started the copy
   */
  DocumentController live(DocumentController asking) {
    for (ApplicationController window : set.windows()) {
      if (window.documents() == asking) {
        return asking;
      }
    }
    ApplicationController active = set.target();
    return active == null ? asking : active.documents();
  }

  private void expire() {
    if (done == null) {
      return;
    }
    ApplicationController window = set.target();
    if (window == null) {
      quitNow();
      return;
    }
    List<String> targets = new ArrayList<String>();
    for (Path target : running) {
      targets.add(target.toString());
    }
    prompt =
        window
            .view()
            .askUnfinishedCopies(
                targets,
                new Runnable() {
                  @Override
                  public void run() {
                    prompt = null;
                    quitNow();
                  }
                },
                new Runnable() {
                  @Override
                  public void run() {
                    prompt = null;
                    if (done != null) {
                      ui.timerExec((int) BOUND.toMillis(), expired);
                    }
                  }
                });
  }

  /** Runs the waiting step without the running copies, which stop counting. */
  private void quitNow() {
    if (done == null) {
      return;
    }
    LOG.warn(
        "Quitting before Save a Copy As finished, copies may be incomplete: {}",
        LogText.oneLine(running.toString()));
    abandoned.addAll(running);
    running.clear();
    run(done);
  }

  private void run(Runnable step) {
    if (step != null) {
      if (prompt != null) {
        Runnable close = prompt;
        prompt = null;
        close.run();
      }
      ui.cancelTimer(expired);
      done = null;
      failed = null;
      step.run();
    }
  }
}
