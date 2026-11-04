package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A portable run writes its settings inside the bundle and nothing else: two runs over a bundle
 * with a {@code qcaret.conf} marker, the second reading the theme and placement the first saved,
 * leave every other file under the test tree as it was and the home directory empty.
 */
@Tag("swt")
class PortableRunTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);

  @TempDir Path temp;

  @Test
  void aPortableRunWritesOnlyInsideItsBundle() throws Exception {
    Path bundle = Files.createDirectories(temp.resolve("bundle"));
    Files.createFile(bundle.resolve(SettingsLocation.MARKER));
    Path home = Files.createDirectories(temp.resolve("home"));
    SettingsLocation location =
        SettingsLocation.resolve(
            null, bundle.toString(), "Linux", Map.<String, String>of(), home.toString());
    assertEquals(SettingsMode.PORTABLE, location.mode());
    Map<Path, Long> before = files(temp);
    String userHome = System.getProperty("user.home");
    try {
      PlatformSetup.apply("Linux", location);
      assertEquals(ThemeMode.LIGHT, run(location, true));
      assertEquals(ThemeMode.DARK, run(location, false));
    } finally {
      System.setProperty("user.home", userHome);
    }
    Map<Path, Long> after = files(temp);
    List<Path> outside = new ArrayList<Path>();
    for (Map.Entry<Path, Long> entry : after.entrySet()) {
      Long old = before.get(entry.getKey());
      if (!entry.getKey().startsWith(bundle) && !entry.getValue().equals(old)) {
        outside.add(entry.getKey());
      }
    }
    assertEquals(List.of(), outside);
    assertEquals(List.of(), List.copyOf(files(home).keySet()));
    String settings =
        Files.readString(bundle.resolve(SettingsLocation.MARKER), StandardCharsets.ISO_8859_1);
    assertTrue(settings.contains("ui.theme=dark\n"), settings);
    assertTrue(settings.contains("window.width="), settings);
  }

  /** One run: the first switches to the dark theme. Returns the theme the run started with. */
  private ThemeMode run(SettingsLocation location, final boolean first) {
    ConfigStore config = new ConfigStore(location);
    Preferences loaded = config.load();
    StartupRequest request = new StartupRequest(List.of(), null, null, null, null, null, false);
    final boolean[] done = new boolean[1];
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
                  @Override
                  public void run() {
                    boolean ready = window.shell().isVisible();
                    if (ready && first) {
                      controller.execute(CommandId.THEME_DARK);
                    }
                    if (ready || System.nanoTime() > deadline) {
                      done[0] = ready;
                      controller.quit();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(0, new Gui(request, config, loaded, err, observer, LogFiles.NONE).run());
    assertTrue(done[0], "window not shown");
    return loaded.theme();
  }

  /** Returns every regular file under a directory with its modification time. */
  private static Map<Path, Long> files(Path root) throws IOException {
    final Map<Path, Long> found = new HashMap<Path, Long>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            found.put(file, attributes.lastModifiedTime().toMillis());
            return FileVisitResult.CONTINUE;
          }
        });
    return found;
  }
}
