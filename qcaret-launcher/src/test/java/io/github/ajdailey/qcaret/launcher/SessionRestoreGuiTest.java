package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.SessionMode;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code session.restore} through the composed application: each start a {@link Gui} run in this
 * JVM over one settings directory. With the preference off a start that names nothing keeps the
 * session's Recent Files and opens none of its windows; {@code --session} opens them.
 */
@Tag("swt")
class SessionRestoreGuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);

  @TempDir Path temp;
  private Path settings;
  private Path a;

  @BeforeEach
  void files() throws IOException {
    settings = Files.createDirectory(temp.resolve("settings"));
    a = Files.writeString(temp.resolve("a.txt"), "a\n").toRealPath();
  }

  /** What a run showed once its tabs loaded and the recovery rows it waits for arrived. */
  private static class Probe implements Gui.Observer {
    final int rows;
    int tabs = -1;
    Path active;
    int offered = -1;

    Probe(int rows) {
      this.rows = rows;
    }

    @Override
    public void opened(
        final MainWindow window, final ApplicationController controller, Workers workers) {
      final Display display = window.shell().getDisplay();
      final long deadline = System.nanoTime() + BOUND_NANOS;
      display.timerExec(
          20,
          new Runnable() {
            @Override
            public void run() {
              int found = controller.recoveryInput().rows().size();
              boolean loaded =
                  controller.documents().loading() == 0 && controller.documents().count() > 0;
              if ((loaded && found >= rows) || System.nanoTime() > deadline) {
                tabs = controller.documents().count();
                active = controller.documents().activePath();
                offered = found;
                done(controller);
              } else {
                display.timerExec(20, this);
              }
            }
          });
    }

    void done(ApplicationController controller) {
      controller.quit();
    }
  }

  private void conf(String text) throws IOException {
    Files.writeString(settings.resolve("qcaret.conf"), text);
  }

  private static StartupRequest request(List<Path> paths, SessionMode mode) {
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (Path path : paths) {
      files.add(new FileRequest(path));
    }
    return new StartupRequest(files, 800, 500, null, null, null, false, false, mode, null, null);
  }

  private int run(StartupRequest request, Gui.Observer observer) {
    ConfigStore config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return new Gui(request, config, config.load(), err, observer, LogFiles.NONE).run();
  }

  private Session session() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)).load();
  }

  private List<String> sessionTabs() {
    List<String> paths = new ArrayList<String>();
    for (Session.Window window : session().windows()) {
      for (Session.Tab tab : window.tabs()) {
        paths.add(tab.path());
      }
    }
    return paths;
  }

  @Test
  void theSessionOptionWinsOverThePreference() throws Exception {
    assertEquals(0, run(request(List.of(a), SessionMode.RESTORE), new Probe(0)));
    assertEquals(List.of(a.toString()), sessionTabs());
    conf("session.restore=false\n");

    Probe option = new Probe(0);
    assertEquals(0, run(request(List.of(), SessionMode.RESTORE), option));
    assertEquals(1, option.tabs);
    assertEquals(a, option.active);

    Probe plain = new Probe(0);
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), plain));
    assertEquals(1, plain.tabs);
    assertNull(plain.active);
    assertFalse(sessionTabs().contains(a.toString()));
    assertTrue(session().recent().contains(a.toString()), session().recent().toString());

    assertEquals(0, run(request(List.of(a), SessionMode.RESTORE), new Probe(0)));
    conf("session.restore=true\n");
    Probe on = new Probe(0);
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), on));
    assertEquals(a, on.active);
  }

  @Test
  void snapshotBuffersAreOfferedForRecoveryWhenTheWindowsStayClosed() throws Exception {
    conf("session.snapshot=true\n");
    Probe snapshot =
        new Probe(0) {
          @Override
          void done(ApplicationController controller) {
            controller.documents().activeEditor().type("mock unsaved");
            controller.requestQuit();
          }
        };
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), snapshot));
    assertTrue(Files.isDirectory(settings.resolve(RecoveryStore.SNAPSHOT)));
    conf("session.snapshot=true\nsession.restore=false\n");

    Probe offered = new Probe(1);
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), offered));
    assertEquals(1, offered.offered);
    assertNull(offered.active);
  }
}
