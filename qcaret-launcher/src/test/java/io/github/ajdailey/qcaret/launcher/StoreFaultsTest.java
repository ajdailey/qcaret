package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup over a malformed, oversized or unknown-version session file opens an untitled document
 * and logs a WARN; a remembered encoding the runtime does not support opens the file with
 * detection.
 */
@Tag("swt")
class StoreFaultsTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);

  @TempDir Path temp;

  private Path settings(String name) throws IOException {
    return Files.createDirectories(temp.resolve(name));
  }

  private static List<String> recentSince(int from) {
    List<String> lines = LogConfigurator.RECENT.snapshot();
    return new ArrayList<String>(lines.subList(Math.min(from, lines.size()), lines.size()));
  }

  /** What one run showed. */
  private static final class Observed {
    int exit = -1;
    int documents = -1;
    String encoding;
  }

  private Observed start(Path directory, final int expected) {
    ConfigStore config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, directory));
    StartupRequest request = new StartupRequest(List.of(), 800, 500, null, null, null, false);
    final Observed observed = new Observed();
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
                    boolean ready =
                        controller.documents().count() == expected
                            && controller.documents().loading() == 0;
                    if (ready || System.nanoTime() > deadline) {
                      observed.documents = controller.documents().count();
                      if (controller.documents().activeEditor() != null) {
                        observed.encoding =
                            controller
                                .documents()
                                .activeEditor()
                                .document()
                                .encoding()
                                .charset()
                                .name();
                      }
                      controller.quit();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    observed.exit = new Gui(request, config, config.load(), err, observer, LogFiles.NONE).run();
    return observed;
  }

  private void assertUntitledStartWithWarn(Path directory, String warning) {
    int from = LogConfigurator.RECENT.snapshot().size();
    Observed observed = start(directory, 1);
    assertEquals(0, observed.exit);
    assertEquals(1, observed.documents);
    boolean warned = false;
    for (String line : recentSince(from)) {
      warned |= line.contains("WARN") && line.contains(warning);
    }
    assertTrue(warned, recentSince(from).toString());
    assertTrue(Files.isRegularFile(directory.resolve(SessionStore.FILE + ".invalid")));
  }

  @Test
  void aMalformedSessionStartsUntitledWithAWarn() throws IOException {
    Path directory = settings("malformed");
    Files.writeString(
        directory.resolve(SessionStore.FILE),
        "version=1\nwindow.0.active=0\nwindow.0.tab.0.path=\\uZZZZ\n",
        StandardCharsets.ISO_8859_1);
    assertUntitledStartWithWarn(directory, "Malformed");
  }

  @Test
  void anOversizedSessionStartsUntitledWithAWarn() throws IOException {
    Path directory = settings("oversized");
    StringBuilder text = new StringBuilder("version=1\nwindow.0.active=0\n");
    String path = "/mock/" + "a".repeat(1000) + ".txt";
    for (int i = 0; text.length() <= SessionStore.MAX_BYTES; i++) {
      text.append("window.0.tab.").append(i).append(".path=").append(path).append('\n');
    }
    Files.writeString(directory.resolve(SessionStore.FILE), text, StandardCharsets.ISO_8859_1);
    assertUntitledStartWithWarn(directory, "Cannot read");
  }

  @Test
  void anUnknownSessionVersionStartsUntitledWithAWarn() throws IOException {
    Path directory = settings("version");
    Files.writeString(
        directory.resolve(SessionStore.FILE),
        "version=99\nwindow.0.active=0\nwindow.0.tab.0.path=/mock/a.txt\n",
        StandardCharsets.ISO_8859_1);
    assertUntitledStartWithWarn(directory, "Malformed");
  }

  @Test
  void anUnsupportedRememberedEncodingOpensWithDetection() throws IOException {
    Path directory = settings("encoding");
    Path file = Files.writeString(temp.resolve("a.txt"), "mock line\n", StandardCharsets.US_ASCII);
    String path = file.toRealPath().toString().replace("\\", "\\\\");
    Files.writeString(
        directory.resolve(SessionStore.FILE),
        "version=1\nwindow.0.active=0\nwindow.0.tab.0.tabWidth=4\nwindow.0.tab.0.path="
            + path
            + "\nwindow.0.tab.0.encoding=x-mock-unknown\n",
        StandardCharsets.ISO_8859_1);
    Observed observed = start(directory, 1);
    assertEquals(0, observed.exit);
    assertNotEquals("x-mock-unknown", observed.encoding);
    assertEquals("UTF-8", observed.encoding);
  }
}
