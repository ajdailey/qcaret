package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * A {@link WindowSet} over {@link FakeView}s and a {@link ManualUiDispatcher}, its factory
 * recording each window. Call {@link #close} last.
 */
final class Windows {
  final ManualUiDispatcher ui = new ManualUiDispatcher();
  final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  final Workers workers;
  final MemoryBudget budget = new MemoryBudget(1L << 30, 60);
  final RecentLog recent = new RecentLog(50, 200);
  final WindowSet set;
  final List<FakeView> views = new ArrayList<FakeView>();
  final List<ApplicationController> controllers = new ArrayList<ApplicationController>();
  final List<WindowGeometry.Request> geometries = new ArrayList<WindowGeometry.Request>();
  final List<Boolean> maximized = new ArrayList<Boolean>();

  Windows(Path temp, boolean remember) {
    this(temp, remember, Preferences.DEFAULTS);
  }

  Windows(Path temp, boolean remember, Preferences preferences) {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    set =
        new WindowSet(
            workers,
            ui,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            preferences,
            Harness.LIGHT_SYSTEM,
            budget,
            Keymap.defaults(false),
            remember);
    set.setLogging(recent, LogFiles.NONE);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(
              WindowSet owner, WindowGeometry.Request at, boolean opensMaximized) {
            geometries.add(at);
            maximized.add(opensMaximized);
            return window(owner);
          }
        });
    window(set);
  }

  private ApplicationController window(WindowSet owner) {
    FakeView view = new FakeView(ui);
    ApplicationController controller = new ApplicationController(owner, view);
    views.add(view);
    controllers.add(controller);
    return controller;
  }

  ApplicationController first() {
    return controllers.get(0);
  }

  FakeView view(ApplicationController window) {
    return views.get(controllers.indexOf(window));
  }

  /** Opens a second window with no document. */
  ApplicationController second() {
    set.newWindow();
    ApplicationController window = controllers.get(controllers.size() - 1);
    window.documents().closeAll(null);
    return window;
  }

  /** Waits for the condition; a timeout fails with every thread's stack, virtual ones included. */
  void await(BooleanSupplier condition, String what) throws InterruptedException {
    if (!ui.runUntil(condition, Harness.BOUND)) {
      fail(what + " not within " + Harness.BOUND + "\n" + threadDump());
    }
  }

  private static String threadDump() {
    try {
      Path file = Files.createTempFile("qcaret-threads", ".txt");
      try {
        Files.delete(file);
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
        return Files.readString(file);
      } finally {
        Files.deleteIfExists(file);
      }
    } catch (IOException e) {
      return "no thread dump: " + e;
    }
  }

  /** Runs UI tasks until no load runs in any window. */
  void settle(String what) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (int i = 0; i < controllers.size(); i++) {
              if (views.get(i).active.get() != 0 || controllers.get(i).documents().loading() != 0) {
                return false;
              }
            }
            return true;
          }
        },
        what);
    ui.runPending();
  }

  /** Opens a file in a window and waits for its tab. */
  void open(final ApplicationController window, Path file) throws InterruptedException {
    final int before = window.documents().count();
    window.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.documents().count() == before + 1;
          }
        },
        "opened " + file.getFileName());
    settle("open");
  }

  /** Quits, closes the workers and fails on any worker failure. */
  void close() throws InterruptedException {
    set.quit();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (FakeView view : views) {
              if (!view.disposed) {
                return false;
              }
            }
            return true;
          }
        },
        "quit");
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }
}
