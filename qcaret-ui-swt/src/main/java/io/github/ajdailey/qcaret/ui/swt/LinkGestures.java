package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.ui.swt.platform.BrowserBehavior;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which navigation the Browser preview canceled goes to the link handler: one within
 * {@value #GESTURE_MILLIS} ms after a gesture (a primary-button release, or Enter or Space) follows
 * once, and any other is dropped, except a top-frame one {@link BrowserBehavior#followUngestured}
 * follows. Each followed and dropped navigation is logged at debug.
 *
 * <p>Call on the UI thread.
 */
final class LinkGestures {
  private static final Logger LOG = LoggerFactory.getLogger(LinkGestures.class);

  /** Time from a gesture to the navigation it starts. */
  static final int GESTURE_MILLIS = 1000;

  /** What started a gesture. */
  enum Source {
    CLICK("click"),
    KEY("key");

    private final String label;

    Source(String label) {
      this.label = label;
    }
  }

  private final UiDispatcher ui;
  private final BrowserBehavior browser;
  private final Consumer<String> follow;
  private final LongSupplier clock;
  private final Runnable gestureEnd =
      new Runnable() {
        @Override
        public void run() {
          active = false;
          end(clock.getAsLong());
        }
      };
  private boolean active;
  private Source source;
  private long started;
  private boolean anyEnded;
  private long ended;

  /**
   * Creates the rule on the system clock.
   *
   * @param ui runs the gesture's time limit
   * @param browser follows or drops a navigation with no gesture
   * @param follow receives each followed location
   */
  LinkGestures(UiDispatcher ui, BrowserBehavior browser, Consumer<String> follow) {
    this(ui, browser, follow, System::nanoTime);
  }

  /**
   * Creates the rule.
   *
   * @param ui runs the gesture's time limit
   * @param browser follows or drops a navigation with no gesture
   * @param follow receives each followed location
   * @param clock nanoseconds, for the logged ages
   */
  LinkGestures(
      UiDispatcher ui, BrowserBehavior browser, Consumer<String> follow, LongSupplier clock) {
    this.ui = ui;
    this.browser = browser;
    this.follow = follow;
    this.clock = clock;
  }

  /**
   * Records a gesture and opens its time limit.
   *
   * @param from what started it
   */
  void gesture(Source from) {
    active = true;
    source = from;
    started = clock.getAsLong();
    ui.timerExec(GESTURE_MILLIS, gestureEnd);
  }

  /**
   * Takes a navigation the browser canceled: follows it on an open gesture or, for the top frame,
   * where links follow without one, and drops it otherwise.
   *
   * @param location the navigation's location
   * @param top true for the top frame
   */
  void canceled(String location, boolean top) {
    long now = clock.getAsLong();
    if (active) {
      active = false;
      ui.cancelTimer(gestureEnd);
      end(now);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Markdown preview followed {} on a {} {} ms old",
            LogText.oneLine(location),
            LogText.oneLine(source.label),
            millis(now - started));
      }
      follow.accept(location);
    } else if (top && browser.followUngestured(location, follow)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Markdown preview followed {} with no gesture", LogText.oneLine(location));
      }
    } else if (LOG.isDebugEnabled()) {
      drop(location, now);
    }
  }

  private void drop(String location, long at) {
    if (anyEnded) {
      LOG.debug(
          "Markdown preview dropped {}, last gesture ended {} ms before",
          LogText.oneLine(location),
          millis(at - ended));
    } else {
      LOG.debug("Markdown preview dropped {}, last gesture ended none", LogText.oneLine(location));
    }
  }

  private void end(long at) {
    anyEnded = true;
    ended = at;
  }

  private static long millis(long nanos) {
    return nanos / 1_000_000L;
  }
}
