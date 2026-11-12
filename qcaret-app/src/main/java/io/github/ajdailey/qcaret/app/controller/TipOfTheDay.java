package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.about.TipList;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.TipSettings;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.TipDisplay;
import io.github.ajdailey.qcaret.app.view.TipInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.function.BooleanSupplier;

/**
 * A window's Help > Tip of the Day dialog, one at a time. Each tip shown advances {@code
 * help.nextTip}, wrapping, so the next show in any window continues the list; the check box sets
 * {@code help.tipsOnStartup}. With that on, {@link #startup} opens the dialog once the recovery
 * offer has ended and no modal dialog shows in any window, checking again every {@link
 * #RETRY_MILLIS} while one does. Confined to the UI thread.
 */
final class TipOfTheDay implements TipInput {
  /** Delay between checks for a modal dialog at startup. */
  static final int RETRY_MILLIS = 500;

  private final WindowSet set;
  private final MainView view;
  private final UiDispatcher ui;
  private final BooleanSupplier closing;
  private final Runnable retry =
      new Runnable() {
        @Override
        public void run() {
          showAtStartup();
        }
      };
  private TipDisplay display;
  private int waits;

  /**
   * Creates a window's dialog controller.
   *
   * @param set holds the settings, the keys and the windows
   * @param view the window
   * @param ui runs the startup checks
   * @param closing true once the window is closing
   */
  TipOfTheDay(WindowSet set, MainView view, UiDispatcher ui, BooleanSupplier closing) {
    this.set = set;
    this.view = view;
    this.ui = ui;
    this.closing = closing;
  }

  /** Opens the dialog with the next tip, or brings an open one to the front. */
  void open() {
    if (display != null) {
      display.toFront();
      return;
    }
    display = view.showTips(this);
    showNext(display);
  }

  /**
   * Opens the dialog once the startup prompts have ended, when {@code help.tipsOnStartup} is on.
   */
  void startup() {
    if (set.settings().current().tips().onStartup()) {
      set.recovery()
          .afterOffer(
              new Runnable() {
                @Override
                public void run() {
                  showAtStartup();
                }
              });
    }
  }

  private void showAtStartup() {
    if (closing.getAsBoolean() || !set.settings().current().tips().onStartup()) {
      return;
    }
    for (ApplicationController window : set.windows()) {
      if (window.view().promptShown()) {
        waits++;
        ui.timerExec(RETRY_MILLIS, retry);
        return;
      }
    }
    open();
  }

  private void showNext(TipDisplay target) {
    Settings settings = set.settings();
    Preferences current = settings.current();
    TipSettings tips = current.tips();
    int index = tips.next() % TipList.size();
    target.showTip(TipList.text(index, set.keymap()), tips.onStartup());
    settings.set(current.withTips(tips.withNext((index + 1) % TipList.size())));
  }

  @Override
  public void nextTip() {
    if (display != null && !closing.getAsBoolean()) {
      showNext(display);
    }
  }

  @Override
  public void showOnStartup(boolean on) {
    if (!closing.getAsBoolean()) {
      Preferences current = set.settings().current();
      set.settings().set(current.withTips(current.tips().withOnStartup(on)));
    }
  }

  @Override
  public void closed() {
    display = null;
  }

  /**
   * Reports whether the dialog is open.
   *
   * @return true while it shows
   */
  boolean shown() {
    return display != null;
  }

  /**
   * Returns how often the startup show waited for a modal dialog, for tests.
   *
   * @return the count
   */
  int waits() {
    return waits;
  }
}
