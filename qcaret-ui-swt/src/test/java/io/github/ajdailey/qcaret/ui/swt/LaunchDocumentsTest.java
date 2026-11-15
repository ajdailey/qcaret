package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M12: macOS hands a cold Open With's files as open-document events in the first event-loop passes;
 * the start collects them before the first window. Linux raises no such event at startup, so the
 * events here come from queued tasks. G2-D: a launch that hands off waits for the events; the wait
 * tests run a fake clock that timer steps advance, so the stop rule is checked without wall time.
 */
@Tag("swt")
class LaunchDocumentsTest {
  @Test
  void theQueuedEventsAreCollectedInOrder() {
    Display display = new Display();
    try {
      final LaunchDocuments launch = new LaunchDocuments();
      final Path first = Path.of("mock", "first.txt").toAbsolutePath();
      final Path second = Path.of("mock", "second.txt").toAbsolutePath();
      display.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              launch.handleEvent(event(first.toString()));
              launch.handleEvent(event(""));
              launch.handleEvent(event("bad\0name"));
              launch.handleEvent(event(null));
            }
          });
      display.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              launch.handleEvent(event(second.toString()));
            }
          });
      assertEquals(List.of(first, second), LaunchDocuments.collect(display, launch));
    } finally {
      display.dispose();
    }
  }

  @Test
  void aStartWithoutEventsCollectsNothing() {
    Display display = new Display();
    try {
      assertEquals(List.of(), LaunchDocuments.collect(display));
    } finally {
      display.dispose();
    }
  }

  private static Event event(String text) {
    Event event = new Event();
    event.text = text;
    return event;
  }

  @Test
  void theWaitEndsAQuietSpanAfterTheLastEvent() {
    Display display = new Display();
    try {
      long[] now = {0};
      LaunchDocuments launch = new LaunchDocuments(clock(now));
      Script script =
          new Script(
              display,
              launch,
              now,
              new long[] {500, 900, 1050, 1249, 1250, 1300},
              new String[] {null, "first", "second", null, null, "late"});
      display.timerExec(1, script);
      assertEquals(
          List.of(Path.of("first"), Path.of("second")), LaunchDocuments.await(display, launch));
      assertEquals(5, script.ran);
    } finally {
      display.dispose();
    }
  }

  @Test
  void theWaitEndsAfterTheFirstBoundWithoutEvents() {
    Display display = new Display();
    try {
      long[] now = {0};
      LaunchDocuments launch = new LaunchDocuments(clock(now));
      Script script =
          new Script(
              display,
              launch,
              now,
              new long[] {999, 1000, 1001},
              new String[] {null, null, "late"});
      display.timerExec(1, script);
      assertEquals(List.of(), LaunchDocuments.await(display, launch));
      assertEquals(2, script.ran);
    } finally {
      display.dispose();
    }
  }

  @Test
  void theWaitLeftRoundsUpAndFollowsTheLastEvent() {
    long[] now = {0};
    LaunchDocuments launch = new LaunchDocuments(clock(now));
    assertEquals(LaunchDocuments.FIRST_MILLIS, launch.remainingMillis(0));
    now[0] = LaunchDocuments.FIRST_MILLIS * MILLI - 1;
    assertEquals(1, launch.remainingMillis(0));
    now[0] = LaunchDocuments.FIRST_MILLIS * MILLI;
    assertEquals(0, launch.remainingMillis(0));
    now[0] = 100 * MILLI;
    launch.handleEvent(event(null));
    assertEquals(LaunchDocuments.QUIET_MILLIS, launch.remainingMillis(0));
  }

  private static final long MILLI = 1_000_000;

  private static LongSupplier clock(final long[] now) {
    return new LongSupplier() {
      @Override
      public long getAsLong() {
        return now[0];
      }
    };
  }

  /** One step per timer tick: sets the clock to the next time and sends the next event, if any. */
  private static final class Script implements Runnable {
    private final Display display;
    private final LaunchDocuments launch;
    private final long[] now;
    private final long[] millis;
    private final String[] names;
    private int ran;

    Script(Display display, LaunchDocuments launch, long[] now, long[] millis, String[] names) {
      this.display = display;
      this.launch = launch;
      this.now = now;
      this.millis = millis;
      this.names = names;
    }

    @Override
    public void run() {
      now[0] = millis[ran] * MILLI;
      if (names[ran] != null) {
        launch.handleEvent(event(names[ran]));
      }
      ran++;
      if (ran < millis.length) {
        display.timerExec(1, this);
      }
    }
  }
}
