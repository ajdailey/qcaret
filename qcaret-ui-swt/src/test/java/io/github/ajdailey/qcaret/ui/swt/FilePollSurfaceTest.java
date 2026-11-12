package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code file.pollSeconds} from the Preferences dialog to the file monitor. The file changes
 * through a hard link in another directory, which a directory watch on Linux does not report, so
 * only the monitor's poll finds the change.
 */
@Tag("swt")
class FilePollSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @AfterEach
  void close() {
    try {
      if (controller != null) {
        controller.documents().dispose();
      }
      if (workers != null) {
        workers.close();
      }
      if (display != null) {
        display.dispose();
      }
    } finally {
      assertNull(failure.get());
    }
  }

  private static Options polling(int seconds) {
    Options o = Options.DEFAULTS;
    return new Options(
        o.completionChars(),
        o.autoClose(),
        o.maxHighlightLineChars(),
        o.fontSize(),
        seconds,
        true,
        o.backupDays(),
        o.searchMaxLineChars(),
        o.excludedDirectories(),
        o.recoveryIntervalSeconds(),
        o.recoveryKeepDays(),
        o.sessionSnapshot(),
        o.tabWidth(),
        o.insertSpaces(),
        o.rightMargin(),
        o.dateFormat(),
        o.editorConfig(),
        o.modelines(),
        o.closeWindowWithLastTab());
  }

  private MainWindow open(Preferences preferences) {
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
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            preferences,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
            false);
    window.attach(controller);
    window.open();
    return window;
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private String text() {
    return controller.documents().activeEditor().document().text().toString();
  }

  @Test
  void aPollIntervalAppliedInTheDialogReachesTheMonitor() throws Exception {
    Path file =
        Files.writeString(Files.createDirectory(temp.resolve("docs")).resolve("a.txt"), "1");
    Path link = Files.createDirectory(temp.resolve("other")).resolve("a.txt");
    try {
      Files.createLink(link, file);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no hard link: " + e);
    }
    final MainWindow window = open(Preferences.DEFAULTS.withOptions(polling(3600)));
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().count() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
    controller.execute(CommandId.PREFERENCES);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.preferencesDialog() != null;
          }
        },
        "dialog");
    PreferencesDialog dialog = window.preferencesDialog();
    dialog.pollSeconds().setText("1");
    // Apply keeps the dialog open: no window activation checks the files.
    assertTrue(dialog.apply());
    Files.writeString(link, "changed through the link", StandardCharsets.UTF_8);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "changed through the link".equals(text());
          }
        },
        "reloaded by the poll");
    dialog.shell().dispose();
  }
}
