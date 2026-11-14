package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.SessionMode;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.ErrorExitMarker;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An {@code Error} on the UI thread through the composed application: the quit, the marker and the
 * next starts, each a {@link Gui} run in this JVM over one settings directory.
 */
@Tag("swt")
class ErrorExitGuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final String TYPED = "mock unsaved";
  private static final String SKIPPED = UiText.SESSION_ERROR_EXIT.text();
  private static final String SHORT = UiText.SESSION_ERROR_EXIT_LOG.text();

  @TempDir Path temp;
  private Path settings;
  private Path a;
  private Path b;

  @BeforeEach
  void files() throws IOException {
    settings = Files.createDirectory(temp.resolve("settings"));
    a = Files.writeString(temp.resolve("a.txt"), "a\n").toRealPath();
    b = Files.writeString(temp.resolve("b.txt"), "b\n").toRealPath();
  }

  /** What a run showed once its tabs loaded and the recovery rows it waits for arrived. */
  private static class Probe implements Gui.Observer {
    final int rows;
    String notice;
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
      notice = noticeShown(window.shell());
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

  /** Returns the notice text found in a shell's labels, or null. */
  private static String noticeShown(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Label label) {
        String text = label.getText();
        if (text.equals(SKIPPED) || text.equals(SHORT)) {
          return text;
        }
      }
      if (child instanceof Composite composite) {
        String found = noticeShown(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private ConfigStore config() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
  }

  private ErrorExitMarker marker() {
    return new ErrorExitMarker(new SettingsLocation(SettingsMode.EXPLICIT, settings));
  }

  private static StartupRequest request(List<Path> paths, SessionMode mode) {
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (Path path : paths) {
      files.add(new FileRequest(path));
    }
    return new StartupRequest(files, 800, 500, null, null, null, false, false, mode, null, null);
  }

  private int run(StartupRequest request, Gui.Observer observer) {
    ConfigStore config = config();
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return new Gui(request, config, config.load(), err, observer, LogFiles.NONE).run();
  }

  private List<String> sessionTabs() {
    List<String> paths = new ArrayList<String>();
    Session session =
        new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)).load();
    for (Session.Window window : session.windows()) {
      for (Session.Tab tab : window.tabs()) {
        paths.add(tab.path());
      }
    }
    return paths;
  }

  private List<Path> recoveryFiles() throws IOException {
    List<Path> found = new ArrayList<Path>();
    Path root = settings.resolve(RecoveryStore.DIRECTORY);
    if (!Files.isDirectory(root)) {
      return found;
    }
    try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
      for (Path directory : directories) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.qcr")) {
          for (Path file : files) {
            found.add(file);
          }
        }
      }
    }
    return found;
  }

  /** Saves a session holding a.txt. */
  private void saveSessionWithA() {
    assertEquals(0, run(request(List.of(a), SessionMode.RESTORE), new Probe(0)));
    assertEquals(List.of(a.toString()), sessionTabs());
  }

  @Test
  void anErrorQuitsWithTheBufferKeptAndTheNextRestoreSkipsTheTabsOnce() throws Exception {
    saveSessionWithA();
    Probe failing =
        new Probe(0) {
          @Override
          void done(ApplicationController controller) {
            controller.documents().activeEditor().type(TYPED);
            Display display = Display.getCurrent();
            for (int i = 0; i < 2; i++) {
              display.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      throw new StackOverflowError("mock error");
                    }
                  });
            }
          }
        };
    assertEquals(Launcher.EXIT_UI_FAILURE, run(request(List.of(), SessionMode.DEFAULT), failing));
    assertEquals(a, failing.active);
    assertEquals(ErrorExitMarker.State.PENDING, marker().read());
    assertEquals(1, recoveryFiles().size());
    assertEquals(List.of(a.toString()), sessionTabs());

    Probe skipping = new Probe(1);
    assertEquals(0, run(request(List.of(b), SessionMode.RESTORE), skipping));
    assertEquals(SKIPPED, skipping.notice);
    assertEquals(1, skipping.tabs);
    assertEquals(b, skipping.active);
    assertEquals(1, skipping.offered);
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());

    Probe restoring = new Probe(0);
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), restoring));
    assertNull(restoring.notice);
    assertEquals(1, restoring.tabs);
    assertEquals(b, restoring.active);
  }

  @Test
  void aStartWithoutTheSessionShowsTheShortNoticeOnce() throws Exception {
    saveSessionWithA();
    assertTrue(marker().write());
    Probe first = new Probe(0);
    assertEquals(0, run(request(List.of(b), SessionMode.DEFAULT), first));
    assertEquals(SHORT, first.notice);
    assertEquals(1, first.tabs);
    assertEquals(b, first.active);
    assertEquals(ErrorExitMarker.State.SHOWN, marker().read());

    Probe second = new Probe(0);
    assertEquals(0, run(request(List.of(b), SessionMode.DEFAULT), second));
    assertNull(second.notice);

    Probe restoring = new Probe(0);
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), restoring));
    assertNull(restoring.notice);
    assertEquals(1, restoring.tabs);
    assertNull(restoring.active);
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());
    assertFalse(sessionTabs().contains(a.toString()));
  }

  @Test
  void aSkippedSessionsSnapshotBuffersAreOfferedForRecovery() throws Exception {
    Files.writeString(settings.resolve("qcaret.conf"), "session.snapshot=true\n");
    Probe snapshot =
        new Probe(0) {
          @Override
          void done(ApplicationController controller) {
            controller.documents().activeEditor().type(TYPED);
            controller.requestQuit();
          }
        };
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), snapshot));
    assertTrue(Files.isDirectory(settings.resolve(RecoveryStore.SNAPSHOT)));
    assertTrue(marker().write());

    Probe skipping = new Probe(1);
    assertEquals(0, run(request(List.of(), SessionMode.DEFAULT), skipping));
    assertEquals(SKIPPED, skipping.notice);
    assertEquals(1, skipping.offered);
    assertEquals(1, recoveryFiles().size());
  }

  @Test
  void aQuitThatThrowsEndsTheEventLoop() {
    Gui.Observer failing =
        new Gui.Observer() {
          @Override
          public void opened(MainWindow window, ApplicationController controller, Workers workers) {
            // Closed workers run the quit's end on the UI thread, where this listener throws.
            controller
                .windowSet()
                .addQuitListener(
                    new Runnable() {
                      @Override
                      public void run() {
                        throw new IllegalStateException("mock quit failure");
                      }
                    });
            workers.close(Duration.ZERO);
            window
                .shell()
                .getDisplay()
                .asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        throw new StackOverflowError("mock error");
                      }
                    });
          }
        };
    assertEquals(Launcher.EXIT_UI_FAILURE, run(request(List.of(a), SessionMode.DEFAULT), failing));
    assertEquals(ErrorExitMarker.State.PENDING, marker().read());
  }
}
