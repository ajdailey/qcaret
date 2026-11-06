package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.SessionMode;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.ipc.InstanceClient;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two main windows through the composed application, a real handoff into them, and the quit. Which
 * window takes the file is {@code WindowSetTest}'s: a late {@code SWT.Activate} moves the target
 * here, under load and without a window manager.
 */
@Tag("swt")
class WindowsGuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);

  @TempDir Path temp;

  @Test
  void aHandoffReachesTheRunningInstanceAndQuitClosesBothWindows() throws Exception {
    final Path settings = Files.createDirectory(temp.resolve("settings"));
    final Path first = Files.writeString(temp.resolve("first.txt"), "one\n");
    final Path handed = Files.writeString(temp.resolve("handed.txt"), "two\n");
    InstanceServer server = InstanceServer.claim(settings);
    assertNotNull(server);
    StartupRequest request =
        new StartupRequest(List.of(new FileRequest(first)), 800, 500, null, null, null, false);
    final List<String> observed = new CopyOnWriteArrayList<String>();
    final Workers[] owned = new Workers[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, final Workers workers) {
            owned[0] = workers;
            final WindowSet set = controller.windowSet();
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            controller.execute(CommandId.NEW_WINDOW);
            observed.add("windows " + set.windows().size());
            workers.start(
                Workers.Kind.IPC,
                new Runnable() {
                  @Override
                  public void run() {
                    try {
                      InstanceClient.handOff(
                          settings,
                          StartupRequest.handoff(
                              List.of(new FileRequest(handed, 1, 2, true, null)),
                              false,
                              null,
                              null,
                              null,
                              null,
                              null,
                              null));
                      observed.add("handed");
                    } catch (IOException e) {
                      observed.add("handoff failed: " + e.getMessage());
                    }
                  }
                });
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    int open = 0;
                    for (ApplicationController each : set.windows()) {
                      open += each.documents().count();
                    }
                    if (open == 3 || System.nanoTime() > deadline) {
                      observed.add("documents " + open);
                      controller.quit();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
                Preferences.DEFAULTS,
                err,
                observer,
                server,
                LogFiles.NONE,
                false)
            .run());
    assertTrue(owned[0].isClosed());
    assertTrue(observed.contains("windows 2"), observed.toString());
    assertTrue(observed.contains("handed"), observed.toString());
    assertTrue(observed.contains("documents 3"), observed.toString());
    assertFalse(Files.exists(settings.resolve("instance.endpoint")));
  }

  /** A launch that connects before the primary's composition starts waits, then shows as a tab. */
  @Test
  void aHandoffSentBeforeTheFirstWindowOpensAsATab() throws Exception {
    final Path settings = Files.createDirectory(temp.resolve("settings"));
    Path first = Files.writeString(temp.resolve("first.txt"), "one\n");
    Path handed = Files.writeString(temp.resolve("handed.txt"), "two\n");
    InstanceServer server = InstanceServer.claim(settings);
    assertNotNull(server);
    final StartupRequest launch =
        StartupRequest.handoff(
            List.of(new FileRequest(handed)), false, null, null, null, null, null, null);
    final List<String> observed = new CopyOnWriteArrayList<String>();
    try (Workers client = new Workers(1, new Launcher.FailureLogger())) {
      client.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              try {
                InstanceClient.handOff(settings, launch);
                observed.add("handed");
              } catch (IOException e) {
                observed.add("handoff failed: " + e.getMessage());
              }
            }
          });
      Gui.Observer observer =
          new Gui.Observer() {
            @Override
            public void opened(
                MainWindow window, final ApplicationController controller, Workers workers) {
              final Display display = window.shell().getDisplay();
              final long deadline = System.nanoTime() + BOUND_NANOS;
              display.timerExec(
                  20,
                  new Runnable() {
                    @Override
                    public void run() {
                      if (controller.documents().count() == 2 || System.nanoTime() > deadline) {
                        observed.add("tabs " + controller.documents().count());
                        controller.quit();
                      } else {
                        display.timerExec(20, this);
                      }
                    }
                  });
            }
          };
      PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
      StartupRequest request =
          new StartupRequest(List.of(new FileRequest(first)), 800, 500, null, null, null, false);
      assertEquals(
          0,
          new Gui(
                  request,
                  new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
                  Preferences.DEFAULTS,
                  err,
                  observer,
                  server,
                  LogFiles.NONE,
                  false)
              .run());
    }
    assertTrue(observed.contains("tabs 2"), observed.toString());
    assertTrue(observed.contains("handed"), observed.toString());
  }

  private static Session.Window savedWindow(Path file) {
    Session.View view = new Session.View("", 0, "");
    Session.Tab tab =
        new Session.Tab(
            file.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(view),
            0,
            false,
            "",
            "",
            List.of(),
            false);
    return new Session.Window(
        new WindowState(40, 40, 500, 300, false),
        List.of(tab),
        0,
        "",
        false,
        "",
        List.of(),
        false,
        "",
        List.of(),
        List.of(),
        Session.Display.DEFAULTS);
  }

  /** Runs a start over the settings and returns the window and document counts it reached. */
  private static String run(
      StartupRequest request, Path settings, final int windows, final int documents) {
    final String[] reached = new String[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(MainWindow window, final ApplicationController controller, Workers w) {
            final WindowSet set = controller.windowSet();
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    int open = 0;
                    for (ApplicationController each : set.windows()) {
                      open += each.documents().count();
                    }
                    reached[0] = set.windows().size() + " windows, " + open + " documents";
                    boolean done = set.windows().size() == windows && open == documents;
                    if (done || System.nanoTime() > deadline) {
                      controller.quit();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
                Preferences.DEFAULTS,
                err,
                observer,
                LogFiles.NONE)
            .run());
    return reached[0];
  }

  @Test
  void aStartRestoresEveryWindowUnlessTheCommandLineSaysNot() throws Exception {
    Path settings = Files.createDirectory(temp.resolve("settings"));
    Path a = Files.writeString(temp.resolve("a.txt"), "a\n").toRealPath();
    Path b = Files.writeString(temp.resolve("b.txt"), "b\n").toRealPath();
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    Session saved = new Session(List.of(savedWindow(a), savedWindow(b)), List.of(), List.of());
    assertTrue(store.save(saved));

    StartupRequest skip =
        new StartupRequest(
            List.of(), null, null, null, null, null, false, false, SessionMode.SKIP, null, null);
    assertEquals("1 windows, 1 documents", run(skip, settings, 1, 1));

    StartupRequest plain = new StartupRequest(List.of(), null, null, null, null, null, false);
    assertEquals("2 windows, 2 documents", run(plain, settings, 2, 2));
  }
}
