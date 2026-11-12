package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.Appearance;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup to shutdown through the composed application, on the test thread as UI thread. The
 * position assertions need X11 (Xvfb), not Wayland, and are skipped on SWT GTK 4, which cannot move
 * a window.
 */
@Tag("swt")
class GuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final boolean POSITIONED = !"1".equals(System.getenv("SWT_GTK4"));
  private static final String UNTITLED = "Untitled 1 - QCaret";

  @TempDir Path temp;

  @BeforeEach
  void createSettings() throws Exception {
    Files.createDirectory(temp.resolve("settings"));
  }

  /**
   * Waits for the client area and the title, records them and the location, then quits. The title
   * follows a document on a later UI turn, which SWT runs only once the startup events drain.
   */
  private static final class Recorder implements Gui.Observer {
    final int width;
    final int height;
    final String title;
    final List<String> observed = new ArrayList<String>();
    Workers workers;

    Recorder(int width, int height, String title) {
      this.width = width;
      this.height = height;
      this.title = title;
    }

    @Override
    public void opened(
        final MainWindow window, final ApplicationController controller, Workers owned) {
      workers = owned;
      final Display display = window.shell().getDisplay();
      final long deadline = System.nanoTime() + BOUND_NANOS;
      display.timerExec(
          20,
          new Runnable() {
            @Override
            public void run() {
              Rectangle area = window.shell().getClientArea();
              String shown = window.shell().getText();
              boolean ready = area.width == width && area.height == height && title.equals(shown);
              if (ready || System.nanoTime() > deadline) {
                observed.add(shown);
                observed.add(area.width + "x" + area.height);
                if (POSITIONED) {
                  observed.add(window.shell().getLocation().toString());
                }
                controller.quit();
              } else {
                display.timerExec(20, this);
              }
            }
          });
    }
  }

  private ConfigStore config() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private static StartupRequest request(
      List<FileRequest> files, Integer width, Integer height, Integer x, Integer y, boolean multi) {
    return new StartupRequest(files, width, height, x, y, null, multi);
  }

  private int run(StartupRequest request, Preferences preferences, Recorder recorder) {
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return new Gui(request, config(), preferences, err, recorder, LogFiles.NONE).run();
  }

  @Test
  void theTitleIsSetBeforeTheEventLoopStarts() {
    final String[] title = new String[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(MainWindow window, ApplicationController controller, Workers owned) {
            title[0] = window.shell().getText();
            controller.quit();
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    StartupRequest request = request(List.of(), 600, 400, 0, 0, true);
    assertEquals(
        0, new Gui(request, config(), Preferences.DEFAULTS, err, observer, LogFiles.NONE).run());
    assertEquals(UNTITLED, title[0]);
  }

  @Test
  void aListenerThatThrowsCostsOneEventAndTheLoopContinues() {
    final List<String> ran = new ArrayList<String>();
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers owned) {
            window
                .shell()
                .addListener(
                    SWT.Help,
                    new Listener() {
                      @Override
                      public void handleEvent(Event event) {
                        ran.add("listener");
                        throw new IllegalStateException("mock listener failure");
                      }
                    });
            Display display = window.shell().getDisplay();
            display.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    window.shell().notifyListeners(SWT.Help, new Event());
                  }
                });
            display.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    ran.add("next");
                    controller.quit();
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    StartupRequest request = request(List.of(), 600, 400, 0, 0, true);
    assertEquals(
        0, new Gui(request, config(), Preferences.DEFAULTS, err, observer, LogFiles.NONE).run());
    assertEquals(List.of("listener", "next"), ran);
  }

  /** ISO-8859-1 whose first decoder request after {@link #arm} blocks until {@link #release}. */
  private static final class GatedCharset extends Charset {
    private final AtomicBoolean armed = new AtomicBoolean();
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile boolean held;

    GatedCharset() {
      super("X-QCARET-TEST-GATE", null);
    }

    void arm() {
      armed.set(true);
    }

    boolean held() {
      return held;
    }

    void release() {
      released.countDown();
    }

    @Override
    public boolean contains(Charset other) {
      return StandardCharsets.ISO_8859_1.contains(other);
    }

    @Override
    public CharsetDecoder newDecoder() {
      if (armed.compareAndSet(true, false)) {
        held = true;
        try {
          released.await(BOUND_NANOS, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return StandardCharsets.ISO_8859_1.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
      return StandardCharsets.ISO_8859_1.newEncoder();
    }
  }

  /** Reopens the file once its tab shows, quits with the reopen's read held, then lets it go. */
  private static final class QuitDuringALoad implements Gui.Observer {
    final GatedCharset gate = new GatedCharset();
    boolean quitHeldALoad;
    Workers workers;

    @Override
    public void opened(MainWindow window, final ApplicationController controller, Workers owned) {
      workers = owned;
      final Display display = window.shell().getDisplay();
      final long deadline = System.nanoTime() + BOUND_NANOS;
      display.timerExec(
          20,
          new Runnable() {
            private boolean reopened;

            @Override
            public void run() {
              if (!reopened && controller.documents().activePath() != null) {
                reopened = true;
                gate.arm();
                controller.documents().reopenWithEncoding(gate);
              }
              if (gate.held() || System.nanoTime() > deadline) {
                quitHeldALoad = gate.held() && controller.documents().loading() == 1;
                controller.quit();
                gate.release();
              } else {
                display.timerExec(20, this);
              }
            }
          });
    }
  }

  @Test
  void aQuitDuringALoadEndsCleanly() throws Exception {
    Path file = Files.writeString(temp.resolve("notes.txt"), "mock\n");
    final QuitDuringALoad observer = new QuitDuringALoad();
    StartupRequest request = request(List.of(new FileRequest(file)), 600, 400, 0, 0, true);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0, new Gui(request, config(), Preferences.DEFAULTS, err, observer, LogFiles.NONE).run());
    assertTrue(observer.quitHeldALoad, "the quit ran with no load held");
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return observer.workers.liveThreads() == 0;
              }
            },
            Duration.ofNanos(BOUND_NANOS)),
        observer.workers.liveThreadNames());
  }

  @Test
  void opensAtTheRequestedSizeThenShutsDownWithoutLeakingThreads() throws Exception {
    Path file = Files.createFile(temp.resolve("notes.txt"));
    String title = "notes.txt - " + file.toRealPath() + " - QCaret";
    final Recorder recorder = new Recorder(900, 600, title);
    StartupRequest request = request(List.of(new FileRequest(file)), 900, 600, 50, 40, true);
    assertEquals(0, run(request, Preferences.DEFAULTS, recorder));
    assertEquals(placed(title, "900x600", "Point {50, 40}"), recorder.observed);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return recorder.workers.liveThreads() == 0;
              }
            },
            Duration.ofSeconds(10)));
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      assertTrue(!thread.getName().startsWith("qcaret-") || !thread.isAlive(), thread.getName());
    }
  }

  @Test
  void aSavedThemeFileColorsTheWindowBeforeItOpensOnTheThemeSide() throws Exception {
    Path themes = Files.createDirectory(temp.resolve("settings").resolve("themes"));
    Files.writeString(
        themes.resolve("mock.theme"), "light.background = #123456\ndark.background = #654321\n");
    final List<Palette> palettes = new ArrayList<Palette>();
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, Workers workers) {
            palettes.add(window.palette());
            window
                .shell()
                .getDisplay()
                .timerExec(
                    20,
                    new Runnable() {
                      @Override
                      public void run() {
                        controller.quit();
                      }
                    });
          }
        };
    Preferences preferences =
        Preferences.DEFAULTS
            .withTheme(ThemeMode.DARK)
            .withAppearance(new Appearance("mock", "", EditorSchemes.FOLLOW));
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    StartupRequest request = request(List.of(), null, null, null, null, true);
    assertEquals(0, new Gui(request, config(), preferences, err, observer, LogFiles.NONE).run());
    assertEquals(new Rgb(0x65, 0x43, 0x21), palettes.get(0).background());
  }

  @Test
  void theRememberedPlacementOpensAndIsSavedAgain() {
    Preferences remembered =
        Preferences.DEFAULTS.withWindow(new WindowState(60, 50, 700, 500, false));
    assertTrue(config().save(Preferences.DEFAULTS, remembered));
    Recorder recorder = new Recorder(700, 500, UNTITLED);
    assertEquals(0, run(request(List.of(), null, null, null, null, false), remembered, recorder));
    assertEquals(placed(UNTITLED, "700x500", "Point {60, 50}"), recorder.observed);
    WindowState saved = config().load().window();
    assertEquals(remembered.window().width(), saved.width());
    assertEquals(remembered.window().height(), saved.height());
    if (POSITIONED) {
      assertEquals(remembered.window(), saved);
    }
  }

  @Test
  void aCommandLinePlacementIsNotSavedBack() {
    Preferences remembered =
        Preferences.DEFAULTS.withWindow(new WindowState(60, 50, 700, 500, false));
    assertTrue(config().save(Preferences.DEFAULTS, remembered));
    Recorder recorder = new Recorder(900, 500, UNTITLED);
    assertEquals(0, run(request(List.of(), 900, null, null, null, false), remembered, recorder));
    assertEquals("900x500", recorder.observed.get(1));
    assertEquals(remembered.window(), config().load().window());
  }

  @Test
  void aMultiInstanceDoesNotSaveThePlacement() {
    // A size smaller than the CI runners' 1024 x 768 screens, so the window is not clamped.
    Preferences remembered =
        Preferences.DEFAULTS.withWindow(new WindowState(60, 50, 700, 500, false));
    Recorder recorder = new Recorder(700, 500, UNTITLED);
    assertEquals(0, run(request(List.of(), null, null, null, null, true), remembered, recorder));
    assertEquals("700x500", recorder.observed.get(1));
    assertEquals(WindowState.UNKNOWN, config().load().window());
  }

  @Test
  void aLaunchBesideAPrimaryRestoresNoSessionAndSavesNoSessionOrPlacement() throws Exception {
    Path file = Files.writeString(temp.resolve("saved.txt"), "saved\n");
    Session saved =
        new Session(
            List.of(
                new Session.Window(
                    WindowState.UNKNOWN,
                    List.of(
                        new Session.Tab(
                            file.toString(),
                            "",
                            "",
                            "",
                            "",
                            false,
                            4,
                            false,
                            List.of(new Session.View("0,0", 0, "")),
                            0,
                            false,
                            "",
                            "",
                            List.of(),
                            false)),
                    0,
                    "",
                    false,
                    "",
                    List.of(),
                    false,
                    "",
                    List.of(),
                    List.of(),
                    Session.Display.DEFAULTS,
                    "")),
            List.of(file.toString()),
            List.of());
    SessionStore sessions = new SessionStore(config().location());
    assertTrue(sessions.save(saved));
    Path session = config().location().directory().resolve(SessionStore.FILE);
    byte[] before = Files.readAllBytes(session);
    final List<Integer> tabs = new ArrayList<Integer>();
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, Workers workers) {
            tabs.add(controller.documents().count());
            tabs.add(controller.windowSet().windows().size());
            window
                .shell()
                .getDisplay()
                .asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        controller.quit();
                      }
                    });
          }
        };
    StartupRequest request = request(List.of(), null, null, null, null, false);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    new Gui(request, config(), Preferences.DEFAULTS, err, observer, null, LogFiles.NONE, true)
        .run();
    assertEquals(List.of(1, 1), tabs);
    assertArrayEquals(before, Files.readAllBytes(session));
    assertEquals(WindowState.UNKNOWN, config().load().window());
  }

  /** The title, client size and, where positions apply, the location the recorder observes. */
  private static List<String> placed(String title, String size, String location) {
    return POSITIONED ? List.of(title, size, location) : List.of(title, size);
  }
}
