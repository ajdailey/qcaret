package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * The link gesture rule without a browser: a navigation after its gesture follows once, one with no
 * gesture or after the gesture expired is dropped, and where links follow without a gesture every
 * canceled top-frame navigation follows and a frame's does not. Timers run only when a test fires
 * them.
 */
class LinkGesturesTest {
  private final Timers timers = new Timers();
  private final List<String> followed = new ArrayList<String>();
  private final long[] now = new long[1];

  private LinkGestures gestures(boolean ungestured) {
    return new LinkGestures(
        timers,
        ungestured,
        new Consumer<String>() {
          @Override
          public void accept(String location) {
            followed.add(location);
          }
        },
        new LongSupplier() {
          @Override
          public long getAsLong() {
            return now[0];
          }
        });
  }

  @Test
  void aNavigationAfterItsGestureFollowsOnce() {
    LinkGestures gestures = gestures(false);
    gestures.gesture(LinkGestures.Source.CLICK);
    now[0] += 5_000_000L;
    gestures.canceled("https://example.invalid/a", true);
    gestures.canceled("https://example.invalid/b", true);
    assertEquals(List.of("https://example.invalid/a"), followed);
    assertEquals(List.of(), timers.delays(), "the gesture's time limit is canceled");
  }

  @Test
  void aNavigationWithNoGestureIsDropped() {
    LinkGestures gestures = gestures(false);
    gestures.canceled("https://example.invalid/refresh", true);
    gestures.gesture(LinkGestures.Source.CLICK);
    assertEquals(List.of(), followed);
  }

  @Test
  void anExpiredGestureFollowsNothing() {
    LinkGestures gestures = gestures(false);
    gestures.gesture(LinkGestures.Source.KEY);
    timers.fire(LinkGestures.GESTURE_MILLIS);
    gestures.canceled("https://example.invalid/a", true);
    assertEquals(List.of(), followed);
  }

  @Test
  void withoutGesturesEveryTopNavigationFollows() {
    LinkGestures gestures = gestures(true);
    gestures.canceled("https://example.invalid/a", true);
    gestures.canceled("https://example.invalid/b", true);
    gestures.gesture(LinkGestures.Source.CLICK);
    timers.fire(LinkGestures.GESTURE_MILLIS);
    gestures.canceled("https://example.invalid/c", true);
    gestures.canceled("https://example.invalid/frame", false);
    assertEquals(
        List.of(
            "https://example.invalid/a", "https://example.invalid/b", "https://example.invalid/c"),
        followed);
  }

  /** Records timers and runs one only when the test fires it. */
  private static final class Timers implements UiDispatcher {
    private final Map<Runnable, Integer> pending = new LinkedHashMap<Runnable, Integer>();

    @Override
    public void asyncExec(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      pending.put(task, delayMillis);
    }

    @Override
    public void cancelTimer(Runnable task) {
      pending.remove(task);
    }

    @Override
    public boolean isUiThread() {
      return true;
    }

    List<Integer> delays() {
      return new ArrayList<Integer>(pending.values());
    }

    void fire(int delayMillis) {
      Runnable due = null;
      for (Map.Entry<Runnable, Integer> entry : pending.entrySet()) {
        if (entry.getValue() == delayMillis) {
          due = entry.getKey();
        }
      }
      assertTrue(due != null, "a timer of " + delayMillis + " ms is armed");
      pending.remove(due);
      due.run();
    }
  }
}
