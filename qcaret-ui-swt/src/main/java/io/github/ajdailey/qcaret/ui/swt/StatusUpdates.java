package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Schedules a window's status update: the title, the status fields and the menu and toolbar edit
 * state. Requests before the update runs share it. While an editor of the window holds its redraws
 * ({@link io.github.ajdailey.qcaret.app.view.EditorView#holdRedraw}), as a macro run's view does,
 * updates wait for the release, which posts one; an update that runs while a view holds waits
 * again. A macro run releases its view for one turn at each refresh, so the update runs and paints
 * with the view. Confined to the UI thread.
 */
final class StatusUpdates {
  private final UiDispatcher dispatcher;
  private final Runnable show;
  private final Runnable task =
      new Runnable() {
        @Override
        public void run() {
          pending = false;
          if (holds > 0) {
            deferred = true;
          } else {
            shown++;
            show.run();
          }
        }
      };
  private boolean pending;
  private boolean deferred;
  private int holds;
  private int requests;
  private int shown;
  private int releases;

  /**
   * Creates the schedule.
   *
   * @param dispatcher posts the update
   * @param show shows the status now
   */
  StatusUpdates(UiDispatcher dispatcher, Runnable show) {
    this.dispatcher = dispatcher;
    this.show = show;
  }

  /** Posts an update for a later UI turn; while a view holds, the update waits for the release. */
  void request() {
    requests++;
    if (holds > 0) {
      deferred = true;
    } else {
      post();
    }
  }

  /**
   * Counts a view's redraw hold or release; a release posts a waiting update.
   *
   * @param held true for a hold
   */
  void hold(boolean held) {
    if (held) {
      holds++;
      return;
    }
    holds = Math.max(0, holds - 1);
    releases++;
    if (deferred) {
      deferred = false;
      post();
    }
  }

  /**
   * Lays out a status bar after a field's text changed and paints the bar and its fields before
   * returning. On win32 a label paints its new text when it is set, and the area a moved field
   * uncovers keeps the old text until the bar paints, which a macro run's turns delay.
   *
   * @param bar the status bar
   */
  static void layout(Composite bar) {
    bar.layout(true);
    bar.update();
    for (Control child : bar.getChildren()) {
      child.update();
    }
  }

  private void post() {
    if (!pending) {
      pending = true;
      dispatcher.asyncExec(task);
    }
  }

  /** Returns how many requests {@link #request} counted. */
  int requests() {
    return requests;
  }

  /** Returns how many updates ran. */
  int shown() {
    return shown;
  }

  /** Returns how many releases {@link #hold} counted. */
  int releases() {
    return releases;
  }
}
