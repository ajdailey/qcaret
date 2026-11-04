package io.github.ajdailey.qcaret.ui.swt;

import java.io.PrintStream;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Ends a test that hangs. A test still running {@code qcaret.test.hangSeconds} (default 120) after
 * its {@code @BeforeEach} started, its {@code @AfterEach} included, gets every thread's stack
 * printed to stderr, and the test JVM halts with status 3 five seconds later. The bound runs on a
 * daemon timer thread, so it fires while the test thread, the main thread on macOS, is blocked in a
 * native call. Registered for every test in this module through {@code
 * META-INF/services/org.junit.jupiter.api.extension.Extension}.
 */
public final class HangWatchdog implements BeforeEachCallback, AfterEachCallback {
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(HangWatchdog.class);
  private static final long GRACE_MILLIS = 5_000L;
  private static final Timer TIMER = new Timer("ui-test-watchdog", true);

  @Override
  public void beforeEach(ExtensionContext context) {
    final String test = context.getUniqueId();
    final long seconds = Long.getLong("qcaret.test.hangSeconds", 120L);
    TimerTask task =
        new TimerTask() {
          @Override
          public void run() {
            dump(System.err, test, seconds);
            TIMER.schedule(
                new TimerTask() {
                  @Override
                  public void run() {
                    Runtime.getRuntime().halt(3);
                  }
                },
                GRACE_MILLIS);
          }
        };
    context.getStore(NAMESPACE).put(TimerTask.class, task);
    TIMER.schedule(task, TimeUnit.SECONDS.toMillis(seconds));
  }

  @Override
  public void afterEach(ExtensionContext context) {
    TimerTask task = context.getStore(NAMESPACE).remove(TimerTask.class, TimerTask.class);
    if (task != null) {
      task.cancel();
    }
  }

  /** Prints every live thread's stack, the test thread's among them. */
  static void dump(PrintStream out, String test, long seconds) {
    StringBuilder text = new StringBuilder();
    text.append("HANG: ").append(test).append(" still running after ").append(seconds);
    text.append(" s; thread dump follows, then the test JVM halts\n");
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      Thread thread = entry.getKey();
      text.append('\n').append('"').append(thread.getName()).append('"');
      text.append(thread.isDaemon() ? " daemon" : "").append(' ').append(thread.getState());
      text.append('\n');
      for (StackTraceElement frame : entry.getValue()) {
        text.append("    at ").append(frame).append('\n');
      }
    }
    out.print(text);
    out.flush();
  }
}
