package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code workspace.default} for new and non-restored windows, and a portable session that keeps its
 * tabs, recents, per-file state and workspace across a move of the installation.
 */
class WorkspaceDefaultTest {
  @TempDir Path temp;

  private final List<Windows> open = new ArrayList<Windows>();

  @AfterEach
  void close() throws InterruptedException {
    for (Windows windows : open) {
      if (!windows.views.get(0).disposed) {
        windows.close();
      }
    }
  }

  private Windows windows(String folder) {
    Preferences preferences =
        Preferences.DEFAULTS.withOptions(
            Preferences.DEFAULTS.options().withWorkspaceDefault(folder));
    Windows windows = new Windows(temp, true, preferences);
    open.add(windows);
    return windows;
  }

  private static void awaitRoot(
      Windows windows, final ApplicationController window, final Path root)
      throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(window.workspace().root());
          }
        },
        "the workspace " + root.getFileName());
  }

  private static void awaitChecks(final Windows windows, final int count)
      throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.set.workspaceDefault().finished() == count;
          }
        },
        count + " default checks");
  }

  private static void awaitRestore(final Windows windows) throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !windows.set.session().restoring();
          }
        },
        "the restore ended");
    windows.settle("restore");
  }

  private SessionStore store(SettingsLocation location) {
    return new SessionStore(location);
  }

  @Test
  void aStartWithoutTheSessionAndANewWindowTakeTheDefault() throws Exception {
    Path folder = Files.createDirectories(temp.resolve("default")).toRealPath();
    Windows windows = windows(folder.toString());
    SessionStore store =
        store(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
    windows.first().startSession(store, store.load(), false);
    awaitRoot(windows, windows.first(), folder);
    ApplicationController second = windows.second();
    awaitRoot(windows, second, folder);
    assertEquals(windows.first().workspace().shown(), second.workspace().shown());
  }

  @Test
  void aRestoredWindowKeepsItsSessionFolder() throws Exception {
    Path folder = Files.createDirectories(temp.resolve("default")).toRealPath();
    Path saved = Files.createDirectories(temp.resolve("saved")).toRealPath();
    SettingsLocation location =
        new SettingsLocation(SettingsMode.EXPLICIT, Files.createDirectories(temp.resolve("s")));
    Session.Window first = blankWindow(saved.toString());
    Session.Window other = blankWindow("");
    store(location).save(new Session(List.of(first, other), List.of(), List.of()));
    Windows windows = windows(folder.toString());
    SessionStore store = store(location);
    windows.first().startSession(store, store.load(), true);
    awaitRestore(windows);
    awaitRoot(windows, windows.first(), saved);
    ApplicationController fresh = windows.second();
    awaitRoot(windows, fresh, folder);
    assertEquals(saved, windows.first().workspace().root());
    assertNull(windows.controllers.get(1).workspace().root());
    assertEquals(1, windows.set.workspaceDefault().finished());
  }

  private static Session.Window blankWindow(String workspace) {
    return new Session.Window(
        io.github.ajdailey.qcaret.app.config.WindowState.UNKNOWN,
        List.of(),
        -1,
        workspace,
        false,
        "",
        List.of(),
        false,
        "",
        List.of(),
        List.of(),
        Session.Display.DEFAULTS,
        "",
        new Session.Browse("", "", false),
        false);
  }

  @Test
  void aMissingDefaultLeavesNoWorkspaceAndWarnsOncePerRun() throws Exception {
    final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            if (entry.getLevel().intValue() >= Level.WARNING.intValue()) {
              warnings.add(entry.getMessage());
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Logger logger = Logger.getLogger(WorkspaceDefault.class.getName());
    logger.addHandler(capture);
    try {
      Path file = Files.writeString(temp.resolve("file.txt"), "f");
      Windows windows = windows(temp.resolve("missing").toString());
      SessionStore store =
          store(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
      windows.first().startSession(store, store.load(), false);
      ApplicationController second = windows.second();
      awaitChecks(windows, 2);
      assertNull(windows.first().workspace().root());
      assertNull(second.workspace().root());
      assertEquals(1, warnings.size(), warnings.toString());
      windows
          .set
          .settings()
          .set(
              Preferences.DEFAULTS.withOptions(
                  Preferences.DEFAULTS.options().withWorkspaceDefault(file.toString())));
      ApplicationController third = windows.second();
      awaitChecks(windows, 3);
      assertNull(third.workspace().root());
      assertEquals(1, warnings.size(), warnings.toString());
    } finally {
      logger.removeHandler(capture);
    }
  }

  @Test
  void aMovedPortableInstallKeepsItsTabsRecentsStateAndWorkspace() throws Exception {
    Path install = Files.createDirectories(temp.resolve("install"));
    Files.createFile(install.resolve(SettingsLocation.MARKER));
    Path logs = Files.createDirectories(install.resolve("logs"));
    Path a = Files.writeString(logs.resolve("a.log"), "alpha\nbeta\n").toRealPath();
    Path b = Files.writeString(logs.resolve("b.log"), "one\ntwo\n").toRealPath();
    Windows before = windows("");
    SessionStore store = store(new SettingsLocation(SettingsMode.PORTABLE, install, install));
    before.first().startSession(store, store.load(), true);
    awaitRestore(before);
    before.first().documents().closeAll(null);
    before.open(before.first(), a);
    before.open(before.first(), b);
    before.first().documents().setTabWidth(3);
    before.first().documents().closeActive();
    before.settle("close");
    before.first().workspace().set(logs);
    awaitRoot(before, before.first(), logs.toRealPath());
    before.close();
    String text = Files.readString(install.resolve(SessionStore.FILE));
    assertTrue(text.contains("window.0.tab.0.path=logs/a.log\n"), text);
    assertTrue(text.contains("window.0.workspace=logs\n"), text);

    Path moved = Files.move(install, temp.resolve("moved")).toRealPath();
    Path movedA = moved.resolve("logs").resolve("a.log");
    Path movedB = moved.resolve("logs").resolve("b.log");
    Windows after = windows("");
    SessionStore again = store(new SettingsLocation(SettingsMode.PORTABLE, moved, moved));
    after.first().startSession(again, again.load(), true);
    awaitRestore(after);
    awaitRoot(after, after.first(), moved.resolve("logs"));
    assertEquals(movedA, after.first().documents().activePath());
    assertEquals(List.of(movedB, movedA), after.first().recentFiles().paths());
    Session.FileState state = after.set.session().state(movedB);
    assertNotNull(state);
    assertEquals(3, state.tabWidth());
  }
}
