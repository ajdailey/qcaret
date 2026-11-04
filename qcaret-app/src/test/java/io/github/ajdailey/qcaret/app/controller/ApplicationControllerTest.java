package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private ConfigStore config;
  private boolean showsNow = true;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  /** A system setting that stays light; {@link #showsNow} answers the restart question. */
  private final ThemeService system =
      new ThemeService() {
        @Override
        public boolean systemDark() {
          return false;
        }

        @Override
        public void addListener(ThemeListener listener) {
          // The setting never changes.
        }

        @Override
        public void preferDark(boolean dark) {
          // No native controls.
        }

        @Override
        public boolean showsNow(boolean dark) {
          return showsNow;
        }
      };

  @BeforeEach
  void setUp() {
    ui = new ManualUiDispatcher();
    view = new FakeView(ui);
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  @AfterEach
  void tearDown() {
    workers.close();
    assertNull(failure.get());
  }

  private ApplicationController controller(boolean rememberWindow) {
    return new ApplicationController(
        workers, ui, view, config, Preferences.DEFAULTS, system, rememberWindow);
  }

  private BooleanSupplier saved(final Preferences expected) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return Files.exists(temp.resolve("qcaret.conf")) && expected.equals(config.load());
      }
    };
  }

  @Test
  void aThemeChangeSavesAndRepaints() throws Exception {
    ApplicationController controller = controller(true);
    controller.execute(CommandId.THEME_DARK);
    assertEquals(Palette.DARK, controller.theme().palette());
    assertTrue(ui.runUntil(saved(Preferences.DEFAULTS.withTheme(ThemeMode.DARK)), BOUND));
    assertEquals(0, view.infos.size());
  }

  @Test
  void aThemeNativeControlsShowLaterGetsANotice() throws Exception {
    showsNow = false;
    controller(true).execute(CommandId.THEME_DARK);
    assertEquals(1, view.infos.size());
  }

  @Test
  void quitSavesTheWindowPlacementThenDisposes() throws Exception {
    final ApplicationController controller = controller(true);
    final CountDownLatch finished = new CountDownLatch(1);
    controller.addFinishListener(finished::countDown);
    view.bounds = new WindowGeometry.Request(30, 40, 900, 600);
    view.maximized = true;
    controller.execute(CommandId.QUIT);
    controller.quit();
    assertTrue(view.hidden);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.disposed;
              }
            },
            BOUND));
    assertEquals(0, finished.getCount());
    assertEquals(new WindowState(30, 40, 900, 600, true), config.load().window());
    assertTrue(controller.isQuitting());
  }

  @Test
  void quitWithoutRememberedPlacementWritesNothing() throws Exception {
    ApplicationController controller = controller(false);
    view.bounds = new WindowGeometry.Request(30, 40, 900, 600);
    controller.quit();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.disposed;
              }
            },
            BOUND));
    assertFalse(Files.exists(temp.resolve("qcaret.conf")));
  }

  @Test
  void licensesAreReadOnAWorkerAndShownOnTheUiThread() throws Exception {
    controller(true).showLicenses();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.text != null;
              }
            },
            BOUND));
    assertTrue(view.text.contains("==== LICENSE ===="), view.text);
    assertTrue(view.text.contains("==== NOTICE ===="), view.text);
  }

  @Test
  void aboutCollectsTheRuntimeDetails() {
    controller(true).execute(CommandId.ABOUT);
    assertNotNull(view.about);
    assertEquals("mock toolkit", view.about.toolkit());
    assertEquals(temp, view.about.settingsDirectory());
  }
}
