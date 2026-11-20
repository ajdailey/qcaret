package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help > User Guide: the window or its fallback, the locations the browser may load, a browser
 * exception, and the start and load bounds. A real browser's outcome is never raced against a
 * bound: a window built here gets a dispatcher that never schedules one, and each expiry is called
 * directly. The settings are per-user: in explicit mode on Linux, User Guide skips the window and
 * launches the system's HTML application. The controller reaches the window through a view that
 * records launches instead of opening the system browser.
 */
@Tag("swt")
class GuideWindowTest {
  /** Covers both bounds of a real window, the first WebView2 start on a CI runner included. */
  private static final Duration BOUND = Duration.ofSeconds(90);

  /** Time the posted result of a written copy gets to reach the window. */
  private static final long SETTLE_NANOS = 2_000_000_000L;

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<Object> launched = new CopyOnWriteArrayList<Object>();
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
            launchless(window),
            new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
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

  /** Returns a view that calls the window for everything but a launch, which it records. */
  private MainView launchless(final MainWindow target) {
    return (MainView)
        Proxy.newProxyInstance(
            MainView.class.getClassLoader(),
            new Class<?>[] {MainView.class},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("launch") || name.equals("launchUrl")) {
                  launched.add(args[0]);
                  return true;
                }
                try {
                  return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              }
            });
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
      // No browser on this host: the system's HTML application takes the guide.
      assertFallback(copy);
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
      assertFallback(copy);
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
    assertEquals(List.of(), launched);
  }

  private void assertFallback(Path copy) throws Exception {
    assertEquals(1, launched.size(), launched.toString());
    assertEquals(copy, ((Path) launched.get(0)).toRealPath());
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
            new UnboundDispatcher(new SwtUiDispatcher(display)));
    assertNull(created);
    assertEquals(shells, display.getShells().length);
    assertTrue(fallbacks.isEmpty());
  }

  /** Creates a window on the guide's file whose browser carries the given listeners first. */
  private GuideWindow guide(
      Path copy, final LocationAdapter locations, final ProgressAdapter progress) {
    return GuideWindow.create(
        window.shell(),
        copy,
        new Consumer<Path>() {
          @Override
          public void accept(Path file) {
            launched.add(file);
          }
        },
        new GuideWindow.BrowserFactory() {
          @Override
          public Browser create(Composite parent) {
            Browser browser = GuideWindow.BROWSER.create(parent);
            if (locations != null) {
              browser.addLocationListener(locations);
            }
            if (progress != null) {
              browser.addProgressListener(progress);
            }
            return browser;
          }
        },
        new UnboundDispatcher(new SwtUiDispatcher(display)));
  }

  private Path page() throws Exception {
    return Files.writeString(
        temp.resolve("guide.html"), "<html><body><p>Mock guide</p></body></html>");
  }

  @Test
  void aLoadedGuideMeetsTheBounds() throws Exception {
    BrowserHosts.assumeBrowser(window.shell());
    // Listeners added in the factory run before the window's own, so each records the bounds as
    // the window's previous event left them.
    final List<String> atNavigation = new ArrayList<String>();
    final List<String> atCompleted = new ArrayList<String>();
    final GuideWindow[] created = new GuideWindow[1];
    final GuideWindow guide =
        guide(
            page(),
            new LocationAdapter() {
              @Override
              public void changing(LocationEvent event) {
                if (event.location != null && event.location.startsWith("file:")) {
                  atNavigation.add(armed(created[0]));
                }
              }
            },
            new ProgressAdapter() {
              @Override
              public void completed(ProgressEvent event) {
                if (!atNavigation.isEmpty()) {
                  atCompleted.add(armed(created[0]));
                }
              }
            });
    assertNotNull(guide);
    created[0] = guide;
    assertTrue(guide.start().armed());
    assertFalse(guide.bound().armed());
    guide.open();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return guide.loaded();
              }
            }),
        "location " + guide.location());
    assertEquals("true false", atNavigation.get(0), "start bound, load bound at navigation");
    assertEquals("false true", atCompleted.get(0), "start bound, load bound at completed");
    assertFalse(guide.start().armed());
    assertFalse(guide.bound().armed());
    assertEquals(List.of(), launched);
  }

  private static String armed(GuideWindow guide) {
    return guide == null ? "none" : guide.start().armed() + " " + guide.bound().armed();
  }

  @Test
  void theStartBoundExpiringTakesTheFallback() throws Exception {
    BrowserHosts.assumeBrowser(window.shell());
    Path copy = page();
    GuideWindow guide = guide(copy, null, null);
    assertNotNull(guide);
    assertTrue(guide.start().armed());
    guide.start().run();
    assertTrue(guide.shell().isDisposed());
    assertEquals(List.of(copy), launched);
    assertFalse(guide.start().armed());
    assertFalse(guide.bound().armed());
  }

  @Test
  void theLoadBoundExpiringTakesTheFallback() throws Exception {
    BrowserHosts.assumeBrowser(window.shell());
    Path copy = page();
    GuideWindow guide = guide(copy, null, null);
    assertNotNull(guide);
    guide.start().met();
    guide.bound().arm();
    guide.bound().run();
    assertTrue(guide.shell().isDisposed());
    assertEquals(List.of(copy), launched);
    assertFalse(guide.bound().armed());
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
