package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.DocumentController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionLock;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import io.github.ajdailey.qcaret.ui.swt.platform.LaunchDocuments;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start that names files through the composed application, with {@code session.snapshot} on: with
 * the session lock free it restores the session and its Quit asks nothing; with the lock held it
 * restores nothing and its Quit asks. Files from open-document events (macOS Open With) start the
 * same way as command-line files.
 */
@Tag("swt")
class StartWithFilesGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;
  private Path settings;
  private Path a;
  private Path b;

  @BeforeEach
  void files() throws IOException {
    settings = Files.createDirectory(temp.resolve("settings"));
    Files.writeString(settings.resolve("qcaret.conf"), "session.snapshot=true\n");
    a = Files.writeString(temp.resolve("a.txt"), "a\n").toRealPath();
    b = Files.writeString(temp.resolve("b.txt"), "b\n").toRealPath();
  }

  /** Waits for the tabs to load, records them, then quits; a failed assertion ends the run. */
  private static class Script implements Gui.Observer {
    final int tabs;
    final List<Path> paths = new ArrayList<Path>();
    Path active;
    String dirtyText;
    boolean asked;

    Script(int tabs) {
      this.tabs = tabs;
    }

    @Override
    public void opened(MainWindow window, final ApplicationController controller, Workers w) {
      final Display display = window.shell().getDisplay();
      final DocumentController documents = controller.documents();
      assertTrue(
          UiLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return documents.count() == tabs && documents.loading() == 0;
                }
              },
              BOUND),
          tabs + " tabs");
      active = documents.activePath();
      for (int i = 0; i < documents.count(); i++) {
        documents.select(i);
        paths.add(documents.activePath());
        if (documents.activeEditor().document().isDirty()) {
          dirtyText = documents.activeEditor().document().text().toString();
        }
      }
      act(display, controller);
    }

    void act(Display display, ApplicationController controller) {
      controller.quit();
    }
  }

  /** Types into the selected tab and requests Quit; answers Don't Save if it asks. */
  private static class TypeAndQuit extends Script {
    TypeAndQuit(int tabs) {
      super(tabs);
    }

    @Override
    void act(final Display display, final ApplicationController controller) {
      controller.documents().select(controller.documents().count() - 1);
      controller.documents().activeEditor().type("x");
      controller.requestQuit();
      final String title = UiText.UNSAVED_TITLE.text();
      assertTrue(
          UiLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return !UiLoop.shells(display, title).isEmpty()
                      || controller.windowSet().windows().isEmpty();
                }
              },
              BOUND),
          "no prompt and windows open");
      List<Shell> prompts = UiLoop.shells(display, title);
      asked = !prompts.isEmpty();
      if (asked) {
        assertTrue(UiLoop.press(prompts.get(0), UiText.BUTTON_DONT_SAVE.text()));
      }
    }
  }

  private static StartupRequest request(Path... paths) {
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (Path path : paths) {
      files.add(new FileRequest(path));
    }
    return new StartupRequest(files, 800, 500, null, null, null, false);
  }

  private Gui gui(StartupRequest request, Gui.Observer observer) {
    ConfigStore config = new ConfigStore(location());
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return new Gui(request, config, config.load(), err, observer, LogFiles.NONE);
  }

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, settings);
  }

  private List<Path> sessionTabs() {
    List<Path> paths = new ArrayList<Path>();
    for (Session.Window window : new SessionStore(location()).load().windows()) {
      for (Session.Tab tab : window.tabs()) {
        paths.add(Path.of(tab.path()));
      }
    }
    return paths;
  }

  private static void assertSame(Path expected, Path actual) throws IOException {
    assertNotNull(actual, "no path for " + expected);
    assertTrue(Files.isSameFile(expected, actual), actual + " is not " + expected);
  }

  @Test
  void aStartWithFilesRestoresTheSessionAndItsQuitAsksNothing() throws Exception {
    assertEquals(0, gui(request(a), new Script(1)).run());
    TypeAndQuit typed = new TypeAndQuit(2);
    assertEquals(0, gui(request(b), typed).run());
    assertSame(a, typed.paths.get(0));
    assertSame(b, typed.paths.get(1));
    assertSame(b, typed.active);
    assertFalse(typed.asked, "the quit asked");

    Script next = new Script(2);
    assertEquals(0, gui(request(), next).run());
    assertSame(a, next.paths.get(0));
    assertSame(b, next.paths.get(1));
    assertEquals("xb\n", next.dirtyText);
  }

  @Test
  void aStartWithFilesWhileAnotherInstanceHoldsTheSessionRestoresNothingAndAsks() throws Exception {
    assertEquals(0, gui(request(a), new Script(1)).run());
    TypeAndQuit typed = new TypeAndQuit(1);
    try (SessionLock held = SessionLock.acquire(location())) {
      assertNotNull(held);
      assertEquals(0, gui(request(b), typed).run());
    }
    assertSame(b, typed.active);
    assertTrue(typed.asked, "the quit did not ask");
    assertEquals(1, sessionTabs().size());
    assertSame(a, sessionTabs().get(0));
  }

  @Test
  void filesFromOpenDocumentEventsOwnTheSessionAsCommandLineFilesDo() throws Exception {
    assertEquals(0, gui(request(a), new Script(1)).run());
    LaunchDocuments events =
        new LaunchDocuments() {
          @Override
          public List<Path> collect(Display display) {
            return List.of(b);
          }

          @Override
          public List<String> await(Wait wait) {
            return List.of();
          }
        };
    Script opened = new Script(2);
    assertEquals(0, gui(request(), opened).withDocumentEvents(events).run());
    assertSame(b, opened.active);
    assertEquals(2, sessionTabs().size());
    assertSame(a, sessionTabs().get(0));
    assertSame(b, sessionTabs().get(1));
  }
}
