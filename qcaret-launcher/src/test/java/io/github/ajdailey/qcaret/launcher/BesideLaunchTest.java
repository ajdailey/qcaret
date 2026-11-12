package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.workspace.HomeFolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code Launcher.run} in this JVM while a primary holds the lock and its endpoint is gone: the
 * launch opens one untitled window beside it, lists the session's recent files, restores none of
 * its windows, and saves neither the session nor the placement.
 */
@Tag("swt")
class BesideLaunchTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final String OPENED = "Main window open";

  @TempDir Path temp;

  private static Session.Window savedWindow(Path file) {
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
            List.of(new Session.View("", 0, "")),
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
        Session.Display.DEFAULTS,
        "");
  }

  /** Every menu entry's text under a menu, shown first so the File menu lists its files. */
  private static void texts(Menu menu, List<String> into) {
    menu.notifyListeners(SWT.Show, new Event());
    for (MenuItem item : menu.getItems()) {
      into.add(item.getText());
      if (item.getMenu() != null) {
        texts(item.getMenu(), into);
      }
    }
  }

  /** Records the main windows' titles and menu entries once a title shows, then closes them. */
  private static void inspectThenClose(final Display display, final List<String> seen) {
    final long deadline = System.nanoTime() + BOUND_NANOS;
    display.timerExec(
        20,
        new Runnable() {
          @Override
          public void run() {
            List<Shell> windows = new ArrayList<Shell>();
            for (Shell shell : display.getShells()) {
              if (shell.getParent() == null && shell.getText().endsWith("QCaret")) {
                windows.add(shell);
              }
            }
            if (windows.isEmpty() && System.nanoTime() < deadline) {
              display.timerExec(20, this);
              return;
            }
            for (Shell shell : windows) {
              seen.add("title " + shell.getText());
              if (shell.getMenuBar() != null) {
                List<String> entries = new ArrayList<String>();
                for (MenuItem top : shell.getMenuBar().getItems()) {
                  if (top.getMenu() != null) {
                    texts(top.getMenu(), entries);
                  }
                }
                seen.add("menus " + String.join("|", entries));
              }
            }
            for (Shell shell : display.getShells()) {
              if (shell.getParent() == null) {
                shell.close();
              }
            }
          }
        });
  }

  private static int windowsOpened() {
    int count = 0;
    for (String line : LogConfigurator.RECENT.snapshot()) {
      if (line.contains(OPENED)) {
        count++;
      }
    }
    return count;
  }

  @Test
  void aLaunchBesideAPrimaryOpensOneUntitledWindowWithTheRecentFilesAndSavesNothing()
      throws Exception {
    Path settings = SafePaths.settingsDirectory(temp.resolve("settings"));
    Path restored = Files.writeString(temp.resolve("restored.txt"), "restored\n").toRealPath();
    Path recent = Files.writeString(temp.resolve("mock.txt"), "recent\n").toRealPath();
    SessionStore sessions = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    assertTrue(
        sessions.save(
            new Session(List.of(savedWindow(restored)), List.of(recent.toString()), List.of())));
    Path sessionFile = settings.resolve(SessionStore.FILE);
    byte[] saved = Files.readAllBytes(sessionFile);
    final Thread self = Thread.currentThread();
    final int before = windowsOpened();
    final List<String> seen = new CopyOnWriteArrayList<String>();
    String home = System.getProperty("user.home");
    String homeFolder = System.getProperty(HomeFolder.PROPERTY);
    Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int status;
    try (InstanceServer held = InstanceServer.claim(settings);
        Workers watcher = new Workers(1, new Launcher.FailureLogger())) {
      assertNotNull(held);
      Files.delete(settings.resolve("instance.endpoint"));
      watcher.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              long deadline = System.nanoTime() + BOUND_NANOS;
              // The geometry line is logged from the running event loop; Display.findDisplay
              // returns the Display while its constructor runs, when asyncExec fails.
              while (windowsOpened() == before && System.nanoTime() < deadline) {
                try {
                  Thread.sleep(20);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
              final Display display = Display.findDisplay(self);
              if (display == null) {
                seen.add("no Display");
                return;
              }
              display.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      inspectThenClose(display, seen);
                    }
                  });
            }
          });
      status =
          new Launcher(
                  new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                  new PrintStream(err, true, StandardCharsets.UTF_8))
              .run(new String[] {"--settings", settings.toString()});
    } finally {
      System.setProperty("user.home", home);
      if (homeFolder == null) {
        System.clearProperty(HomeFolder.PROPERTY);
      } else {
        System.setProperty(HomeFolder.PROPERTY, homeFolder);
      }
      Thread.setDefaultUncaughtExceptionHandler(handler);
    }
    assertEquals(0, status, err.toString(StandardCharsets.UTF_8));
    assertEquals(2, seen.size(), seen.toString());
    assertEquals("title Untitled 1 - QCaret", seen.get(0));
    assertTrue(seen.get(1).contains(recent.toString()), seen.get(1));
    assertFalse(seen.get(1).contains(restored.toString()), seen.get(1));
    assertArrayEquals(saved, Files.readAllBytes(sessionFile));
    Path conf = settings.resolve("qcaret.conf");
    assertFalse(Files.exists(conf) && Files.readString(conf).contains("window.width="));
  }
}
