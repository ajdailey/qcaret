package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.ViewDefaults;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code ui.*} window defaults against View toggles, new windows and the session restore. */
class ViewDefaultsTest {
  /** Every default the opposite of {@link ViewDefaults#DEFAULTS}. */
  private static final ViewDefaults FLIPPED =
      new ViewDefaults(true, true, true, false, false, true);

  @TempDir Path temp;

  private Path settings;
  private final List<Windows> open = new ArrayList<Windows>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
  }

  @AfterEach
  void close() throws InterruptedException {
    for (Windows windows : open) {
      if (!windows.views.get(0).disposed) {
        windows.close();
      }
    }
  }

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, settings);
  }

  private Windows windows(ViewDefaults view) {
    Preferences preferences =
        Preferences.DEFAULTS.withState(Preferences.DEFAULTS.state().withView(view));
    Windows windows = new Windows(temp, true, preferences);
    open.add(windows);
    return windows;
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

  private static void assertShows(ViewDefaults expected, Windows windows, int index) {
    ApplicationController window = windows.controllers.get(index);
    DocumentController documents = window.documents();
    assertEquals(
        expected,
        new ViewDefaults(
            documents.wrap(),
            documents.whitespaceShown(),
            documents.lineEndingsShown(),
            documents.overviewShown(),
            documents.lineNumbersShown(),
            window.workspace().shown()));
    assertEquals(
        expected.lineNumbers(), !Boolean.FALSE.equals(windows.views.get(index).lineNumbersShown));
    assertEquals(expected.workspaceBrowser(), windows.views.get(index).workspaceShown);
  }

  @Test
  void aStartWithoutTheSessionAndANewWindowTakeTheDefaults() throws Exception {
    Windows windows = windows(FLIPPED);
    SessionStore store = new SessionStore(location());
    windows.first().startSession(store, store.load(FLIPPED), false);
    windows.settle("start");
    assertShows(FLIPPED, windows, 0);
    windows.set.newWindow();
    windows.settle("new window");
    assertEquals(2, windows.controllers.size());
    assertShows(FLIPPED, windows, 1);
  }

  @Test
  void aToggleChangesItsWindowOnlyAndWritesItsKey() throws Exception {
    final Windows windows = windows(ViewDefaults.DEFAULTS);
    windows.set.newWindow();
    windows.settle("new window");
    ApplicationController first = windows.first();
    CommandId[] toggles = {
      CommandId.WORD_WRAP,
      CommandId.SHOW_WHITESPACE,
      CommandId.SHOW_LINE_ENDINGS,
      CommandId.OVERVIEW_STRIP,
      CommandId.LINE_NUMBERS,
      CommandId.WORKSPACE_BROWSER
    };
    for (CommandId toggle : toggles) {
      first.execute(toggle);
    }
    assertShows(FLIPPED, windows, 0);
    assertShows(ViewDefaults.DEFAULTS, windows, 1);
    assertEquals(FLIPPED, first.preferences().state().view());
    final ConfigStore file = new ConfigStore(location());
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            windows.ui.runPending();
            return file.load().state().view().equals(FLIPPED);
          }
        },
        "every key saved");
    first.execute(CommandId.WORD_WRAP);
    assertFalse(first.preferences().state().view().wordWrap());
    windows.set.newWindow();
    windows.settle("third window");
    assertShows(FLIPPED.withWordWrap(false), windows, 2);
  }

  @Test
  void aRestoredWindowKeepsItsSessionStateOverTheDefaults() throws Exception {
    SessionStore store = new SessionStore(location());
    Session.Window saved =
        new Session.Window(
            WindowState.UNKNOWN,
            List.of(),
            -1,
            "",
            false,
            "",
            List.of(),
            false,
            "",
            List.of(),
            List.of(),
            Session.Display.DEFAULTS,
            "");
    assertTrue(store.save(new Session(List.of(saved, saved), List.of(), List.of())));
    Windows windows = windows(FLIPPED);
    windows.first().startSession(store, store.load(FLIPPED), true);
    awaitRestore(windows);
    assertEquals(2, windows.controllers.size());
    assertShows(ViewDefaults.DEFAULTS, windows, 0);
    assertShows(ViewDefaults.DEFAULTS, windows, 1);
    assertEquals(FLIPPED, windows.first().preferences().state().view());
  }

  @Test
  void aSessionWindowWithoutTheKeysTakesTheDefaults() throws Exception {
    Files.writeString(
        settings.resolve(SessionStore.FILE),
        "version=1\nwindow.0.active=-1\nwindow.0.wrap=false\n");
    SessionStore store = new SessionStore(location());
    Windows windows = windows(FLIPPED);
    windows.first().startSession(store, store.load(FLIPPED), true);
    awaitRestore(windows);
    assertShows(FLIPPED.withWordWrap(false), windows, 0);
  }
}
