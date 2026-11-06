package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<Preferences> heard = new ArrayList<Preferences>();
  private Workers workers;
  private ConfigStore config;
  private Settings settings;

  @BeforeEach
  void create() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
    settings = settings(config);
  }

  private Settings settings(ConfigStore store) {
    Settings made = new Settings(store, workers, ui, store.load());
    made.addListener(
        new Settings.Listener() {
          @Override
          public void changed(Preferences before, Preferences after) {
            heard.add(after);
          }
        });
    return made;
  }

  @AfterEach
  void close() {
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private ConfigStore other() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  private void refreshed() throws InterruptedException {
    final boolean[] done = new boolean[1];
    settings.refresh(
        new Runnable() {
          @Override
          public void run() {
            done[0] = true;
          }
        });
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return done[0];
              }
            },
            BOUND));
  }

  private boolean saved(Preferences next) throws InterruptedException {
    final Boolean[] result = new Boolean[1];
    settings.set(
        next,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean ok) {
            result[0] = ok;
          }
        });
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return result[0] != null;
              }
            },
            BOUND));
    return result[0];
  }

  @Test
  void aChangeTellsTheListenersAndReachesTheFile() throws Exception {
    Preferences dark = settings.current().withTheme(ThemeMode.DARK);
    assertTrue(saved(dark));
    assertEquals(List.of(dark), heard);
    assertEquals(ThemeMode.DARK, other().load().theme());
  }

  @Test
  void equalSettingsDoNothing() throws Exception {
    assertTrue(saved(settings.current()));
    assertTrue(heard.isEmpty());
    assertFalse(Files.exists(temp.resolve("qcaret.conf")));
  }

  @Test
  void quickChangesLandInOrder() throws Exception {
    Preferences start = settings.current();
    for (int i = 1; i <= 19; i++) {
      settings.set(start.withOptions(Options.DEFAULTS.withCompletionChars(i)));
    }
    assertTrue(saved(start.withOptions(Options.DEFAULTS.withCompletionChars(20))));
    assertEquals(20, other().load().options().completionChars());
  }

  @Test
  void aRecordedChangeWaitsForTheFlush() throws Exception {
    Preferences dark = settings.current().withTheme(ThemeMode.DARK);
    settings.record(dark);
    assertEquals(ThemeMode.LIGHT, other().load().theme());
    settings.flush();
    assertEquals(ThemeMode.DARK, other().load().theme());
  }

  @Test
  void aRefreshTakesUpAnotherProcessesChanges() throws Exception {
    ConfigStore second = other();
    Preferences loaded = second.load();
    Preferences changed = loaded.withOptions(Options.DEFAULTS.withCompletionChars(7));
    assertTrue(second.save(loaded, changed));
    refreshed();
    assertEquals(7, settings.current().options().completionChars());
    assertEquals(List.of(settings.current()), heard);
    assertTrue(saved(settings.current().withTheme(ThemeMode.DARK)));
    Preferences merged = other().load();
    assertEquals(7, merged.options().completionChars());
    assertEquals(ThemeMode.DARK, merged.theme());
  }

  @Test
  void aChangeMadeDuringARefreshSurvivesIt() throws Exception {
    ConfigStore second = other();
    Preferences loaded = second.load();
    assertTrue(second.save(loaded, loaded.withOptions(Options.DEFAULTS.withCompletionChars(7))));
    final boolean[] done = new boolean[1];
    settings.refresh(
        new Runnable() {
          @Override
          public void run() {
            done[0] = true;
          }
        });
    settings.set(settings.current().withTheme(ThemeMode.DARK));
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return done[0];
              }
            },
            BOUND));
    assertEquals(ThemeMode.DARK, settings.current().theme());
    assertEquals(7, settings.current().options().completionChars());
    settings.flush();
    Preferences merged = other().load();
    assertEquals(ThemeMode.DARK, merged.theme());
    assertEquals(7, merged.options().completionChars());
  }

  @Test
  void aSaveFromTheFilesSettingsWritesWhenTheRunningOnesAlreadyMatch() throws Exception {
    final Preferences running = settings.current();
    Preferences dark = running.withTheme(ThemeMode.DARK);
    assertTrue(other().save(running, dark));
    final Boolean[] result = new Boolean[1];
    settings.setFrom(
        dark,
        running,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean ok) {
            result[0] = ok;
          }
        });
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return result[0] != null;
              }
            },
            BOUND));
    assertTrue(result[0]);
    assertEquals(running.theme(), other().load().theme());
    assertTrue(heard.isEmpty());
  }

  @Test
  void aReadOnlyDirectoryKeepsTheRunningSettings() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(temp), "running with permission to write anyway");
      Preferences dark = settings.current().withTheme(ThemeMode.DARK);
      assertFalse(saved(dark));
      refreshed();
      assertEquals(dark, settings.current());
    } finally {
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
