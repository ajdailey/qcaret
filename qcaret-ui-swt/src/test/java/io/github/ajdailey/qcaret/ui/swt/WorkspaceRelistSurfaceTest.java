package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Workspace Browser listing again, from Refresh and from window activation, keeps the selected
 * row and the top row, with and without a new file; a removed selected file leaves the selection on
 * the row listed at its place; a listing with no file change leaves every row item as it was.
 */
@Tag("swt")
class WorkspaceRelistSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int FILES = 80;

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path root;
  private WorkspaceDock dock;
  private Tree tree;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
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
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 900, 520), null);
    window.open();
    root = Files.createDirectory(temp.resolve("mock-project")).toRealPath();
    Files.createDirectory(root.resolve("docs"));
    Files.writeString(root.resolve("docs").resolve("guide.txt"), "Mock guide\n");
    for (int i = 0; i < FILES; i++) {
      write(name(i));
    }
    controller.workspace().set(root);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(controller.workspace().root());
          }
        },
        "workspace set");
    controller.execute(CommandId.WORKSPACE_BROWSER);
    dock = window.workspaceDock();
    tree = dock.tree();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).size() == FILES + 1;
          }
        },
        "root rows");
    idle();
    tree.setSelection(row(name(40)));
    tree.setTopItem(row(name(30)));
    final long[] since = {0};
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            drain();
            if (!top().equals(name(30))) {
              tree.setTopItem(row(name(30)));
              since[0] = 0;
              return false;
            }
            if (since[0] == 0) {
              since[0] = System.nanoTime();
            }
            return System.nanoTime() - since[0] > Duration.ofMillis(300).toNanos();
          }
        },
        "scrolled to " + name(30) + " and settled");
  }

  @AfterEach
  void close() {
    try {
      controller.quit();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
    } finally {
      display.dispose();
      assertNull(failure.get());
    }
  }

  @Test
  void refreshWithNoFileChangeLeavesTheRowsAsTheyWere() {
    TreeItem[] before = tree.getItems();
    refresh();
    assertArrayEquals(before, tree.getItems(), "the same row items");
    assertKept(name(40));
  }

  @Test
  void anActivationWithNoFileChangeLeavesTheRowsAsTheyWere() {
    TreeItem[] before = tree.getItems();
    activate();
    assertArrayEquals(before, tree.getItems(), "the same row items");
    assertKept(name(40));
  }

  @Test
  void refreshWithANewFileKeepsTheSelectionAndTheTopRow() throws Exception {
    write("mock-45a.txt");
    refresh();
    await(listed("mock-45a.txt"), "the new file listed");
    assertKept(name(40));
  }

  @Test
  void anActivationWithANewFileKeepsTheSelectionAndTheTopRow() throws Exception {
    write("mock-45a.txt");
    activate();
    await(listed("mock-45a.txt"), "the new file listed");
    assertKept(name(40));
  }

  @Test
  void aNewFileAboveTheTopRowKeepsTheTopRow() throws Exception {
    assumeFalse(
        "gtk".equals(SWT.getPlatform()),
        "GTK 3 measures a new row after the listing's turn, and the rows move down one row then");
    write("mock-05a.txt");
    activate();
    await(listed("mock-05a.txt"), "the new file listed");
    assertKept(name(40));
  }

  @Test
  void aRemovedSelectedFileLeavesTheSelectionOnTheRowAtItsPlace() throws Exception {
    Files.delete(root.resolve(name(40)));
    refresh();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !dock.texts(null).contains(name(40));
          }
        },
        "the file gone");
    assertKept(name(41));
  }

  private void assertKept(String selected) {
    drain();
    assertEquals(List.of(selected), selection(), "the selection");
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return top().equals(name(30));
          }
        },
        Duration.ofSeconds(5));
    assertEquals(
        name(30),
        top(),
        "the top row; bar "
            + tree.getVerticalBar().getSelection()
            + " row 30 at "
            + row(name(30)).getBounds().y
            + " in "
            + tree.getClientArea());
  }

  private void refresh() {
    dock.press(null, UiText.WORKSPACE_REFRESH);
    idle();
  }

  private void activate() {
    controller.windowActivated();
    idle();
  }

  /** Waits until no read runs, then runs what the reads posted. */
  private void idle() {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !workers.liveThreadNames().contains("qcaret-io-");
          }
        },
        "reads done");
    drain();
  }

  private BooleanSupplier listed(final String name) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return dock.texts(null).contains(name);
      }
    };
  }

  private static String name(int i) {
    return String.format("mock-%02d.txt", i);
  }

  private void write(String name) throws Exception {
    Files.writeString(root.resolve(name), "Mock\n", StandardCharsets.UTF_8);
  }

  private TreeItem row(String name) {
    for (TreeItem item : tree.getItems()) {
      if (item.getText().equals(name)) {
        return item;
      }
    }
    throw new AssertionError(name + " not shown");
  }

  private List<String> selection() {
    List<String> names = new ArrayList<String>();
    for (TreeItem item : tree.getSelection()) {
      names.add(item.getText());
    }
    return names;
  }

  /** Returns the top row; GTK's getTopItem can return a row a setTopItem named before. */
  private String top() {
    if (!"gtk".equals(SWT.getPlatform())) {
      TreeItem item = tree.getTopItem();
      return item == null ? "" : item.getText();
    }
    for (TreeItem item : tree.getItems()) {
      Rectangle bounds = item.getBounds();
      if (bounds.y + bounds.height / 2 > 0) {
        return item.getText();
      }
    }
    return "";
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }
}
