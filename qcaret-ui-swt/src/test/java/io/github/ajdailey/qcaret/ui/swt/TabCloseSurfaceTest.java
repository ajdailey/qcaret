package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab close in a real {@code CTabFolder}: the folder's own reselection of a neighbor, in and out of
 * MRU mode, does not decide the tab shown next.
 */
@Tag("swt")
class TabCloseSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
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
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  /** Opens files {@code t1.txt} to {@code tN.txt} in one request and waits for every tab. */
  private void openTabs(final int count) throws Exception {
    List<Path> files = new ArrayList<Path>();
    for (int i = 1; i <= count; i++) {
      files.add(Files.writeString(temp.resolve("t" + i + ".txt"), "t" + i, StandardCharsets.UTF_8));
    }
    controller.documents().open(files);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == count
                    && controller.documents().loading() == 0;
              }
            },
            BOUND),
        "opened");
    drain();
  }

  /** Asserts the folder, the shown text and the controller all show one file. */
  private void assertShown(String name) {
    CTabFolder tabs = window.tabs();
    assertEquals(name, tabs.getSelection().getText());
    assertEquals(name, controller.documents().activePath().getFileName().toString());
    assertEquals(name.substring(0, name.indexOf('.')), window.activeText().getText());
  }

  private String nameAt(int index) {
    return window.tabs().getItem(index).getText();
  }

  /** Shows the tab at an index, closes it and returns the names of the tabs before and after it. */
  private String[] showAndClose(int index) {
    String before = index > 0 ? nameAt(index - 1) : null;
    String after = index + 1 < window.tabs().getItemCount() ? nameAt(index + 1) : null;
    controller.documents().select(index);
    drain();
    assertShown(nameAt(index));
    controller.execute(CommandId.CLOSE);
    drain();
    return new String[] {before, after};
  }

  @Test
  void closingTheShownTabShowsTheTabAfterItElseTheOneBefore() throws Exception {
    openTabs(4);
    assertShown(showAndClose(1)[1]);
    assertShown(showAndClose(0)[1]);
    assertShown(showAndClose(1)[0]);
  }

  /**
   * {@code TabList} turns MRU mode on to repair a hidden selected tab and to scroll the strip; in
   * MRU mode the folder reselects the last used tab on a dispose. Neither decides the next tab.
   */
  @Test
  void theFolderMruModeLeavesThePositionalRule() throws Exception {
    openTabs(20);
    CTabFolder tabs = window.tabs();
    TabList list = window.tabButtons().tabList();
    controller.documents().select(10);
    drain();
    list.scroll(-1);
    list.showSelection();
    assertFalse(tabs.getMRUVisible());
    assertShown(showAndClose(10)[1]);
    tabs.setMRUVisible(true);
    try {
      controller.documents().select(2);
      drain();
      assertShown(showAndClose(7)[1]);
    } finally {
      tabs.setMRUVisible(false);
    }
  }
}
