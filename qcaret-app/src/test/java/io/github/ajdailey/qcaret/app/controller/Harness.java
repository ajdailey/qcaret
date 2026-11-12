package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * An {@link ApplicationController} over a {@link FakeView} and a {@link ManualUiDispatcher}, with a
 * recent log and a debug log file that records attach and detach. Call {@link #close} last.
 */
final class Harness {
  static final Duration BOUND = Duration.ofSeconds(20);

  final ManualUiDispatcher ui = new ManualUiDispatcher();
  final FakeView view = new FakeView(ui);
  final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  final Workers workers;
  final RecentLog recent = new RecentLog(50, 200);
  final MockLogFiles files;
  final ApplicationController application;

  /** A debug log file that records state only. */
  static final class MockLogFiles implements LogFiles {
    private final Path directory;
    volatile boolean attached;
    volatile boolean fail;

    MockLogFiles(Path directory) {
      this.directory = directory;
    }

    @Override
    public Path directory() {
      return directory;
    }

    @Override
    public boolean attached() {
      return attached;
    }

    @Override
    public void attach() throws IOException {
      if (fail) {
        throw new IOException("mock failure");
      }
      attached = true;
    }

    @Override
    public void detach() {
      attached = false;
    }
  }

  /** A system setting that stays light. */
  static final ThemeService LIGHT_SYSTEM =
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
          return true;
        }
      };

  Harness(Path temp) {
    this(temp, SettingsMode.EXPLICIT);
  }

  Harness(Path temp, SettingsMode mode) {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    files = new MockLogFiles(temp.resolve("logs"));
    application =
        new ApplicationController(
            workers,
            ui,
            view,
            new ConfigStore(new SettingsLocation(mode, temp.resolve("settings"))),
            Preferences.DEFAULTS,
            LIGHT_SYSTEM,
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(false),
            false);
    application.setLogging(recent, files);
  }

  /** Runs UI tasks until the condition holds; fails after {@link #BOUND}. */
  void await(BooleanSupplier condition, String what) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  /** Runs UI tasks until no load, save or conversion is active. */
  void awaitIdle(String what) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.active.get() == 0 && application.documents().loading() == 0;
          }
        },
        what + " left an operation active");
    ui.runPending();
  }

  /** Opens a file and waits for its tab. */
  void open(Path file) throws InterruptedException {
    final int before = application.documents().count();
    application.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return application.documents().count() == before + 1;
          }
        },
        "opened " + file.getFileName());
    awaitIdle("open");
  }

  /** Quits, closes the workers and fails on any worker failure. */
  void close() throws InterruptedException {
    application.quit();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.disposed;
          }
        },
        "quit");
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }
}
