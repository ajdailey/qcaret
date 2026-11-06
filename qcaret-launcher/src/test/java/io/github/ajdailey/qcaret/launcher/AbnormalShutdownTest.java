package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
 * M7's exit condition: a QCaret process killed with a dirty buffer leaves a recovery file, and the
 * next start offers the buffer and restores it. The kill is {@code destroyForcibly}, so no hook
 * runs on either platform.
 */
@Tag("swt")
class AbnormalShutdownTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final String TEXT = "mock unsaved text";

  @TempDir Path temp;

  private static List<Path> recoveryFiles(Path settings) throws IOException {
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

  /** Runs a child that types, waits for its recovery file and kills it. */
  private void crash(Path settings) throws Exception {
    String java = ProcessHandle.current().info().command().orElseThrow();
    Path home = Files.createDirectories(temp.resolve("home"));
    Files.writeString(settings.resolve("qcaret.conf"), "recovery.intervalSeconds=5\n");
    Process child =
        new ProcessBuilder(
                java,
                "-Duser.home=" + home,
                "-cp",
                System.getProperty("java.class.path"),
                CrashChild.class.getName(),
                settings.toString(),
                TEXT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      long deadline = System.nanoTime() + BOUND_NANOS;
      while (recoveryFiles(settings).isEmpty() && child.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(child.isAlive(), "the child ended before the kill");
      assertEquals(1, recoveryFiles(settings).size(), "no recovery file");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    assertFalse(child.isAlive());
  }

  /** What the restart showed. */
  private static final class Observed {
    int rows = -1;
    String text;
    boolean dirty;
  }

  /** Starts in this JVM, restores what the dialog offers and quits. */
  private Observed restart(Path settings) {
    final Observed observed = new Observed();
    ConfigStore config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    StartupRequest request = new StartupRequest(List.of(), 800, 500, null, null, null, false);
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  private boolean restoring;

                  @Override
                  public void run() {
                    RecoveryInput input = controller.recoveryInput();
                    if (!restoring && !input.rows().isEmpty()) {
                      observed.rows = input.rows().size();
                      restoring = true;
                      input.restore(new int[] {0});
                    }
                    Document document = controller.documents().activeEditor().document();
                    String text = document.text().toString();
                    if (TEXT.equals(text) || System.nanoTime() > deadline) {
                      observed.text = text;
                      observed.dirty = document.isDirty();
                      controller.quit();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    new Gui(request, config, config.load(), err, observer, LogFiles.NONE).run();
    return observed;
  }

  @Test
  void aKilledProcessLeavesItsBufferAndTheNextStartOffersIt() throws Exception {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    crash(settings);
    Observed observed = restart(settings);
    assertEquals(1, observed.rows);
    assertEquals(TEXT, observed.text);
    assertTrue(observed.dirty);
  }
}
