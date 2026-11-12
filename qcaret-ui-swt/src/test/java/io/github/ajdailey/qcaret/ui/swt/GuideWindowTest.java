package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help > User Guide: the window or its fallback, the locations the browser may load, a browser
 * exception and the load bound. A real browser's outcome is never raced against a short timer; each
 * outcome has its own deterministic test. The settings are per-user: in explicit mode on Linux,
 * User Guide skips the window and launches the system's HTML application.
 */
@Tag("swt")
class GuideWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  /** Time the posted result of a written copy gets to reach the window. */
  private static final long SETTLE_NANOS = 2_000_000_000L;

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private void settle(long nanos) {
    final long until = System.nanoTime() + nanos;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }

  @Test
  void userGuideShowsTheCopyInABrowserWindowOrNamesTheFile() throws Exception {
    final Path copy = temp.toRealPath().resolve("conf").resolve("cache").resolve("guide.html");
    controller.execute(CommandId.USER_GUIDE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return Files.isRegularFile(copy);
              }
            }));
    settle(SETTLE_NANOS);
    final GuideWindow guide = window.guideWindow();
    if ("1".equals(System.getenv("SWT_GTK4"))) {
      // WebKitGTK 6.0 aborts the JVM, so GTK 4 names the file and creates no browser.
      assertNull(guide);
      assertTrue(window.noticeText().contains(copy.toString()), window.noticeText());
      return;
    }
    if (guide == null) {
      // No browser on this host: the system's HTML application or a notice took the guide.
      return;
    }
    // Either outcome passes: the page loaded, or the window closed and took the fallback. An
    // exception the browser throws on the UI thread escapes runUntil and fails the test.
    boolean settled =
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return guide.shell().isDisposed() || guide.loaded();
              }
            });
    assertTrue(settled, "location " + guide.location());
    if (guide.shell().isDisposed()) {
      return;
    }
    assertTrue(GuideWindow.allowed(guide.location(), copy), guide.location());
    final int loads = guide.loads();
    controller.execute(CommandId.USER_GUIDE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return guide.shell().isDisposed() || guide.loads() > loads && guide.loaded();
              }
            }));
    assertSame(guide, window.guideWindow());
  }

  @Test
  void aGuideArrivingAfterTheWindowClosedIsDropped() {
    int shells = display.getShells().length;
    window.shell().dispose();
    assertTrue(window.showGuide(temp.resolve("guide.html")));
    assertNull(window.guideWindow());
    assertEquals(shells - 1, display.getShells().length);
  }

  @Test
  void theBrowserLoadsOnlyTheGuide() {
    Path copy = temp.resolve("cache").resolve("guide.html");
    assertTrue(GuideWindow.allowed(copy.toUri().toString(), copy));
    assertTrue(GuideWindow.allowed(copy.toUri() + "#search", copy));
    assertTrue(GuideWindow.allowed("about:blank", copy));
    assertFalse(GuideWindow.allowed("https://github.com/ajdailey/qcaret", copy));
    assertFalse(GuideWindow.allowed(temp.resolve("other.html").toUri().toString(), copy));
    assertFalse(GuideWindow.allowed("data:text/html,mock", copy));
    assertFalse(GuideWindow.allowed("javascript:void(0)", copy));
  }

  @Test
  void aBrowserExceptionClosesTheWindowWithoutEscaping() {
    Path copy = temp.resolve("guide.html");
    final List<Path> fallbacks = new CopyOnWriteArrayList<Path>();
    int shells = display.getShells().length;
    GuideWindow created =
        GuideWindow.create(
            window.shell(),
            copy,
            new Consumer<Path>() {
              @Override
              public void accept(Path file) {
                fallbacks.add(file);
              }
            },
            new GuideWindow.BrowserFactory() {
              @Override
              public Browser create(Composite parent) {
                throw new SWTException("Waiting for Edge operation to terminate timed out");
              }
            },
            GuideWindow.LOAD_BOUND_MILLIS);
    assertNull(created);
    assertEquals(shells, display.getShells().length);
    assertTrue(fallbacks.isEmpty());
  }

  @Test
  void theLoadBoundExpiresOnceUnlessMet() {
    final int[] expired = new int[1];
    Runnable count =
        new Runnable() {
          @Override
          public void run() {
            expired[0]++;
          }
        };
    final GuideWindow.LoadBound bound =
        new GuideWindow.LoadBound(new SwtUiDispatcher(display), 1, count);
    bound.arm();
    bound.arm();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return expired[0] > 0;
              }
            }));
    assertFalse(bound.armed());

    GuideWindow.LoadBound met = new GuideWindow.LoadBound(new SwtUiDispatcher(display), 1, count);
    met.arm();
    met.met();
    settle(200_000_000L);
    assertEquals(1, expired[0]);
  }
}
