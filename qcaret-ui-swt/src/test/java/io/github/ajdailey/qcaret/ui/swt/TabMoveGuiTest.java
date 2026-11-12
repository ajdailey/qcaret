package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab moves between main windows under Xvfb: the menu items, a drag released outside every window
 * or over another window's tab strip, and a drag inside the strip that still reorders. The release
 * finds the control under the pointer through a {@link MainWindow.PointerProbe} the test sets.
 * {@code qcaret.shots} names a folder for screenshots.
 */
@Tag("swt")
class TabMoveGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<MainWindow> windows = new ArrayList<MainWindow>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow first;
  private ApplicationController controller;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
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
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(
              WindowSet owner, WindowGeometry.Request at, boolean maximized) {
            return window(owner, at);
          }
        });
    controller = window(set, new WindowGeometry.Request(0, 0, null, null));
    first = windows.get(0);
    controller.documents().open(List.of(file("first.txt", "alpha"), file("second.txt", "beta")));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first.tabs().getItemCount() == 2;
              }
            }));
  }

  private ApplicationController window(WindowSet owner, WindowGeometry.Request at) {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    windows.add(window);
    ApplicationController created = new ApplicationController(owner, window);
    window.attach(created);
    // Without a window manager a window with no position opens at 0,0 over the first one.
    Integer x = at.x() == null ? Integer.valueOf(640) : at.x();
    Integer y = at.y() == null ? Integer.valueOf(0) : at.y();
    window.place(new WindowGeometry.Request(x, y, 620, 420), null);
    window.open();
    return created;
  }

  private Path file(String name, String word) throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 120; i++) {
      text.append(word).append(" line ").append(i).append('\n');
    }
    return Files.writeString(temp.resolve(name), text.toString(), StandardCharsets.US_ASCII);
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (MainWindow window : windows) {
              if (!window.shell().isDisposed()) {
                return false;
              }
            }
            return true;
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private static void mouse(CTabFolder tabs, int type, int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    tabs.notifyListeners(type, event);
  }

  /** Presses on a tab and releases at a display point. */
  private static void drag(CTabFolder tabs, int index, Point release) {
    Rectangle from = tabs.getItem(index).getBounds();
    mouse(tabs, SWT.MouseDown, from.x + from.width / 2, from.y + from.height / 2);
    Point local = tabs.toControl(release);
    mouse(tabs, SWT.MouseUp, local.x, local.y);
  }

  private static void probe(MainWindow window, final Control under) {
    window.setPointerProbe(
        new MainWindow.PointerProbe() {
          @Override
          public Control controlAt(Point point) {
            return under;
          }
        });
  }

  private static MenuItem menuItem(Menu bar, CommandId command) {
    for (MenuItem header : bar.getItems()) {
      Menu menu = header.getMenu();
      if (menu == null) {
        continue;
      }
      for (MenuItem item : menu.getItems()) {
        if (item.getData() == command) {
          return item;
        }
      }
    }
    return null;
  }

  private void shoot(Shell shell, String name) {
    String folder = System.getProperty("qcaret.shots");
    if (folder == null) {
      return;
    }
    runUntil(
        new BooleanSupplier() {
          private final long end = System.nanoTime() + 500_000_000L;

          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > end;
          }
        });
    Rectangle area = shell.getBounds();
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(shell);
    gc.copyArea(image, 0, 0);
    gc.dispose();
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(folder + "/" + name, SWT.IMAGE_PNG);
    image.dispose();
  }

  @Test
  void theMenuItemsMoveTheTabToANewWindow() {
    String text = first.activeText().getText();
    MenuItem item = menuItem(first.shell().getMenuBar(), CommandId.MOVE_TO_NEW_WINDOW);
    assertNotNull(item);
    assertTrue(item.getEnabled());
    item.notifyListeners(SWT.Selection, new Event());
    assertEquals(2, windows.size());
    MainWindow second = windows.get(1);
    assertEquals(text, second.activeText().getText());
    assertEquals(1, second.tabs().getItemCount());
    assertEquals(1, first.tabs().getItemCount());

    String last = first.activeText().getText();
    Menu menu = first.showTabMenu(0);
    menu.setVisible(false);
    for (MenuItem entry : menu.getItems()) {
      if (entry.getText().replace("&", "").equals("Move to New Window")) {
        entry.notifyListeners(SWT.Selection, new Event());
        break;
      }
    }
    assertEquals(3, windows.size());
    assertEquals(last, windows.get(2).activeText().getText());
    assertEquals(0, first.tabs().getItemCount());
    assertFalse(first.shell().isDisposed(), "the emptied window stays open");
    first.menuBar().syncAll();
    assertFalse(menuItem(first.shell().getMenuBar(), CommandId.MOVE_TO_NEW_WINDOW).getEnabled());
  }

  @Test
  void aTabDraggedOutsideEveryWindowOpensANewWindow() {
    CTabFolder tabs = first.tabs();
    String dragged = tabs.getItem(0).getText();
    Rectangle bounds = first.shell().getBounds();
    probe(first, null);
    drag(tabs, 0, new Point(bounds.x + bounds.width - 5, bounds.y + bounds.height - 5));
    assertEquals(1, windows.size(), "a release in its own window's bounds does nothing");
    drag(tabs, 0, new Point(bounds.x + bounds.width + 40, bounds.y + 30));
    assertEquals(2, windows.size());
    assertEquals(1, tabs.getItemCount());
    assertEquals(dragged, windows.get(1).tabs().getItem(0).getText());
    assertTrue(windows.get(1).activeText().getText().contains(" line 120"));
  }

  @Test
  void aTabDroppedOnAnotherWindowsStripMovesThereAndShowsThere() throws Exception {
    controller.execute(CommandId.NEW_WINDOW);
    final MainWindow second = windows.get(1);
    assertEquals(1, second.tabs().getItemCount());
    CTabFolder strip = second.tabs();
    probe(first, second.activeText());
    String appended = first.tabs().getItem(1).getText();
    // GTK 4 opens both windows at 0,0, so a release over the other strip would also hit this one.
    drag(first.tabs(), 1, first.tabs().toDisplay(10, -400));
    assertEquals(2, strip.getItemCount(), "a drop off the other strip appends");
    assertEquals(1, strip.getSelectionIndex());
    assertEquals(appended, strip.getItem(1).getText());
    assertTrue(second.activeText().getText().contains(" line 120"));
    assertEquals(1, first.tabs().getItemCount());
    shoot(first.shell(), "tabmove-source.png");
    shoot(second.shell(), "tabmove-target.png");

    Rectangle over = strip.getItem(1).getBounds();
    probe(first, strip);
    String docked = first.tabs().getItem(0).getText();
    Point at = strip.toDisplay(over.x + over.width / 2, over.y + over.height / 2);
    drag(first.tabs(), 0, new Point(at.x, at.y));
    assertEquals(3, strip.getItemCount());
    assertEquals(1, strip.getSelectionIndex());
    assertEquals(docked, strip.getItem(1).getText());
    assertEquals(0, first.tabs().getItemCount());
    assertFalse(first.shell().isDisposed());
  }

  @Test
  void aMovedTabKeepsItsScrollPositionAndDirtyTitle() throws Exception {
    controller.documents().activeEditor().type("X");
    first.activeEditor().scrollToRow(40);
    final int top = first.activeEditor().topRow();
    assertTrue(top > 0);
    controller.execute(CommandId.MOVE_TO_NEW_WINDOW);
    final MainWindow second = windows.get(1);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return second.activeEditor().topRow() == top;
              }
            }));
    assertTrue(second.tabs().getItem(0).getText().startsWith("*"));
  }

  @Test
  void aDragInsideTheStripStillReorders() {
    CTabFolder tabs = first.tabs();
    first.setPointerProbe(
        new MainWindow.PointerProbe() {
          @Override
          public Control controlAt(Point point) {
            throw new AssertionError("a reorder does not look for another window");
          }
        });
    String moved = tabs.getItem(0).getText();
    Rectangle to = tabs.getItem(1).getBounds();
    drag(tabs, 0, tabs.toDisplay(to.x + to.width / 2, to.y + to.height / 2));
    assertEquals(moved, tabs.getItem(1).getText());
    assertEquals(1, windows.size());
  }

  @Test
  void theWindowMenuListsTheWindows() {
    controller.execute(CommandId.NEW_WINDOW);
    first.menuBar().sync(io.github.ajdailey.qcaret.app.command.CommandGroup.WINDOW);
    Menu bar = first.shell().getMenuBar();
    Menu window = menuItem(bar, CommandId.NEW_WINDOW).getParent();
    int checks = 0;
    for (MenuItem item : window.getItems()) {
      if ((item.getStyle() & SWT.CHECK) != 0) {
        checks++;
      }
    }
    assertEquals(2, checks);
  }

  @Test
  void closingOneWindowDisposesItsShellOnly() {
    controller.execute(CommandId.NEW_WINDOW);
    final MainWindow second = windows.get(1);
    second.shell().close();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return second.shell().isDisposed();
              }
            }));
    assertFalse(first.shell().isDisposed());
    assertEquals(1, set.windows().size());
  }
}
