package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroStep;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.document.Document;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * One macro run against one editor, the window's selected one when it starts. Confined to the UI
 * thread.
 *
 * <ul>
 *   <li>A run of a macro without Undo or Redo steps is one undo step: a {@link Document} group
 *       opens before the first step and closes on every exit path. A macro with them runs without
 *       the group, since Undo cannot run inside one.
 *   <li>While it runs, {@link MacroHooks} drops input to every view of the document and every
 *       window command that is not a view or help command; Esc stops the run.
 *   <li>Steps run in UI turns of at most {@link #TURN_NANOS}, each turn posted through the {@link
 *       UiDispatcher}; a single step longer than that is not split. After a command step the run
 *       resumes once the editor's {@link io.github.ajdailey.qcaret.app.view.BusyCounter} is idle,
 *       so a worker command's result applies before the next step; find steps resume from {@link
 *       MacroFind}'s outcome. A step waits while a rewrap is out, as after a tab-width change in
 *       wrap mode, so a caret move reads the new rows.
 *   <li>The run's view holds its redraws ({@link EditorView#holdRedraw}) and shows the run every
 *       {@link #REFRESH_NANOS} and at the end: the widget's selection and highlight redraws cost
 *       most of a turn's time. A refresh turn releases the view, runs no step and posts the next
 *       turn after the view paints ({@link EditorView#afterPaint}, at most {@link
 *       #PAINT_TIMEOUT_MILLIS}), which holds it again: while turns follow each other with no idle
 *       event loop, cocoa paints nothing until the run ends.
 *   <li>A step fails when its command is disabled, a find or Replace finds no match, a worker
 *       command ends with an error notice ({@link EditorMacros#failed}), or the run's tab is no
 *       longer selected. The run stops there, the completed steps stay, and a notice names the
 *       iteration and the step.
 *   <li>A counted run ends after its count. A run to the end stops when, after an iteration, the
 *       primary selection's end is at the document end or no closer to it than before the
 *       iteration; a find with no match ends it normally.
 * </ul>
 */
final class MacroPlayer {
  /** Longest stretch of steps run in one UI turn. */
  static final long TURN_NANOS = 8_000_000L;

  /** Longest time the run's view holds its redraws before it shows the run once. */
  static final long REFRESH_NANOS = 100_000_000L;

  /** Longest wait after a refresh for the released view to paint. */
  static final int PAINT_TIMEOUT_MILLIS = 50;

  /** Receives a run's progress and end on the UI thread. */
  interface Listener {
    /**
     * Called after each UI turn of the run.
     *
     * @param run the run
     */
    void progress(MacroPlayer run);

    /**
     * Called once when the run ended, on every path.
     *
     * @param run the run
     */
    void ended(MacroPlayer run);
  }

  private enum State {
    READY,
    RUNNING,
    ENDED
  }

  private final ApplicationController window;
  private final DocumentController documents;
  private final EditorController editor;
  private final Document document;
  private final Macro macro;
  private final List<MacroStep> steps;
  private final int count;
  private final MacroHooks hooks;
  private final Listener listener;
  private final UiDispatcher ui;
  private final MacroFind find;
  private final CommandStates states;
  private final Runnable viewsChanged =
      new Runnable() {
        @Override
        public void run() {
          if (state == State.RUNNING && !current()) {
            stop(UiText.MACRO_TAB_CHANGED.text(), false);
          }
        }
      };
  private final Runnable turn =
      new Runnable() {
        @Override
        public void run() {
          turn();
        }
      };
  private LongSupplier nanos = System::nanoTime;
  private EditorView held;
  private long refreshed;
  private boolean released;
  private State state = State.READY;
  private boolean grouped;
  private boolean stepping;
  private boolean waiting;
  private boolean stepAwaited;
  private long waitId;
  private int index;
  private int iterations;
  private long distance;
  private String failure;
  private String notice;

  MacroPlayer(
      ApplicationController window,
      EditorController editor,
      Macro macro,
      int count,
      MacroHooks hooks,
      Listener listener) {
    this.window = window;
    this.documents = window.documents();
    this.editor = editor;
    this.document = editor.document();
    this.macro = macro;
    this.steps = macro.steps();
    this.count = count;
    this.hooks = hooks;
    this.listener = listener;
    this.ui = window.windowSet().ui();
    this.find = new MacroFind(documents, window.windowSet().workers(), ui);
    this.states = new CommandStates(window);
  }

  /** Replaces the clock the turn budget and the refresh read, and restarts the refresh interval. */
  void setNanos(LongSupplier clock) {
    nanos = clock;
    refreshed = clock.getAsLong();
  }

  /**
   * Starts the run with its first turn posted; refuses with a notice a macro without steps and a
   * read-only document.
   *
   * @return false when refused
   */
  boolean start() {
    if (steps.isEmpty()) {
      window.showNotice(UiText.MACRO_EMPTY.text());
      return false;
    }
    if (document.isReadOnly()) {
      window.showNotice(UiText.MACRO_READ_ONLY.text());
      return false;
    }
    state = State.RUNNING;
    editor.completions().close();
    if (!macro.undoes()) {
      document.beginGroup(editor.selections());
      grouped = true;
    }
    distance = distance();
    held = editor.view();
    if (held != null) {
      held.holdRedraw(true);
      refreshed = nanos.getAsLong();
    }
    documents.addActiveListener(viewsChanged);
    ui.asyncExec(turn);
    return true;
  }

  ApplicationController window() {
    return window;
  }

  EditorController editor() {
    return editor;
  }

  Document document() {
    return document;
  }

  /** Reports whether the run is inside a step, whose input the hold lets through. */
  boolean stepping() {
    return stepping;
  }

  /** Reports whether the run has ended. */
  boolean ended() {
    return state == State.ENDED;
  }

  /** Returns the iterations completed. */
  int iterations() {
    return iterations;
  }

  /** Returns the iterations asked for, 0 for a run to the end. */
  int count() {
    return count;
  }

  /** Returns the primary selection's end as a percentage of the document, for a run to the end. */
  int share() {
    int length = document.text().length();
    return length == 0 ? 100 : (int) ((length - distance()) * 100L / length);
  }

  /** Returns the notice the run ended with, or null while it runs and after a silent end. */
  String notice() {
    return notice;
  }

  /**
   * Stops the run at the current step: Esc. The notice names the awaited step, else the next step
   * to run; after an iteration's last step, the first step of the next iteration, or the run ends
   * as that iteration's end check decides.
   */
  void cancel() {
    if (state != State.RUNNING) {
      return;
    }
    editor.cancelWork();
    if (stepAwaited) {
      index--;
    } else if (index == steps.size() && !iterationEnded()) {
      return;
    }
    stop(UiText.MACRO_CANCELED.text(), false);
  }

  /** Ends the run with no notice: its window is closing. */
  void abandon() {
    if (state == State.RUNNING) {
      editor.cancelWork();
      end(null);
    }
  }

  /** Fails the step running or awaited: a worker command ended with an error notice. */
  void failed(String text) {
    if (state == State.RUNNING && (stepping || waiting) && failure == null) {
      failure = text;
    }
  }

  private boolean current() {
    return !editor.disposed() && documents.activeEditor() == editor;
  }

  private long distance() {
    return document.text().length() - editor.selections().selections().primary().end();
  }

  private void turn() {
    if (state != State.RUNNING || waiting) {
      return;
    }
    long started = nanos.getAsLong();
    if (released) {
      released = false;
      held.holdRedraw(true);
      refreshed = started;
    } else if (held != null && started - refreshed >= REFRESH_NANOS) {
      held.holdRedraw(false);
      released = true;
      progress();
      held.afterPaint(PAINT_TIMEOUT_MILLIS, turn);
      return;
    }
    while (state == State.RUNNING) {
      if (index == steps.size() && !iterationEnded()) {
        return;
      }
      if (!current()) {
        stop(UiText.MACRO_TAB_CHANGED.text(), false);
        return;
      }
      if (editor.rewrapping()) {
        awaitWrapped();
        return;
      }
      boolean waits = step(steps.get(index));
      if (state != State.RUNNING) {
        return;
      }
      if (failure != null) {
        stepFailed();
        return;
      }
      index++;
      if (waits) {
        stepAwaited = true;
        return;
      }
      if (nanos.getAsLong() - started >= TURN_NANOS) {
        break;
      }
    }
    progress();
    ui.asyncExec(turn);
  }

  /** Runs one step; returns true when the run resumes from a callback. */
  private boolean step(MacroStep step) {
    stepping = true;
    try {
      switch (step) {
        case MacroStep.Command c -> {
          if (!states.enabled(c.command())) {
            failure = UiText.MACRO_DISABLED.format(c.command().title());
            return false;
          }
          window.execute(c.command());
          if (failure == null) {
            awaitIdle();
            return true;
          }
        }
        case MacroStep.Typed t -> {
          String text = t.text();
          for (int i = 0; i < text.length(); ) {
            int n = Character.charCount(text.codePointAt(i));
            editor.type(text.substring(i, i + n));
            i += n;
          }
        }
        case MacroStep.Insert i -> editor.type(i.text());
        case MacroStep.Complete c -> editor.completions().insertWord(c.word());
        case MacroStep.Find f -> {
          find.find(editor, f, outcome());
          return true;
        }
        case MacroStep.Replace r -> {
          find.replace(editor, r, outcome());
          return true;
        }
        case MacroStep.GoToLine g -> editor.goTo(g.line() - 1, g.column() - 1);
        case MacroStep.TabWidth w -> documents.setTabWidth(w.columns());
        case MacroStep.DateTime d -> window.dateTime().insertAt(editor, d.pattern());
        case MacroStep.Sequence s -> {
          try {
            NumberSequenceInsert.insertInto(editor, s.sequence());
          } catch (ArithmeticException e) {
            failure = UiText.NUMBER_SEQUENCE_OVERFLOW.text();
          }
        }
      }
      return false;
    } finally {
      stepping = false;
    }
  }

  private void awaitIdle() {
    final long awaited = beginWait();
    editor
        .busyCursor()
        .whenIdle(
            new Runnable() {
              @Override
              public void run() {
                resume(awaited, null);
              }
            });
  }

  private void awaitWrapped() {
    final long awaited = beginWait();
    editor.whenWrapped(
        new Runnable() {
          @Override
          public void run() {
            if (state == State.RUNNING && awaited == waitId) {
              waiting = false;
              turn();
            }
          }
        });
  }

  private MacroFind.Outcome outcome() {
    final long awaited = beginWait();
    return new MacroFind.Outcome() {
      @Override
      public void ended(String text) {
        resume(awaited, text);
      }
    };
  }

  private long beginWait() {
    waiting = true;
    return ++waitId;
  }

  private void resume(long awaited, String text) {
    if (state != State.RUNNING || awaited != waitId) {
      return;
    }
    waiting = false;
    stepAwaited = false;
    if (text != null && failure == null) {
      failure = text;
    }
    if (failure != null) {
      index--;
      stepFailed();
      return;
    }
    turn();
  }

  /** Ends the iteration at the last step; returns false when the run ended. */
  private boolean iterationEnded() {
    iterations++;
    boolean done;
    if (count > 0) {
      done = iterations >= count;
    } else {
      long now = distance();
      done = now == 0 || now >= distance;
      distance = now;
    }
    if (done) {
      end(UiText.MACRO_RAN.format(UiText.count(iterations, UiText.COUNT_TIME, UiText.COUNT_TIMES)));
      return false;
    }
    index = 0;
    return true;
  }

  private void stepFailed() {
    String reason = failure;
    boolean noMatch =
        reason.equals(UiText.MACRO_NO_MATCH.text())
            || reason.equals(UiText.MACRO_NO_NEXT_MATCH.text());
    stop(reason, count == 0 && noMatch);
  }

  /** Stops at the current step; a normal end of a run to the end reports the iterations done. */
  private void stop(String reason, boolean normalEnd) {
    if (normalEnd) {
      end(UiText.MACRO_RAN.format(UiText.count(iterations, UiText.COUNT_TIME, UiText.COUNT_TIMES)));
    } else if (count > 0) {
      end(UiText.MACRO_STOPPED.format(iterations + 1, count, index + 1, reason));
    } else {
      end(UiText.MACRO_STOPPED_TO_END.format(iterations + 1, index + 1, reason));
    }
  }

  private void end(String text) {
    if (state == State.ENDED) {
      return;
    }
    state = State.ENDED;
    waiting = false;
    find.cancel();
    documents.removeActiveListener(viewsChanged);
    if (grouped) {
      grouped = false;
      document.endGroup();
      // Undo and Redo are disabled inside a group, and closing one changes no text.
      window.view().editStateChanged();
    }
    if (held != null) {
      held.holdRedraw(false);
      held = null;
    }
    hooks.runEnded(this);
    notice = text;
    if (text != null) {
      window.showNotice(text);
    }
    if (listener != null) {
      listener.ended(this);
    }
  }

  private void progress() {
    if (listener != null) {
      listener.progress(this);
    }
  }
}
