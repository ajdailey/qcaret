package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tab strip's close button, its context menu request and tab drags released where nothing
 * moves, through events sent to the folder under Xvfb.
 */
@Tag("swt")
class TabStripGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<MainWindow> windows = new ArrayList<MainWindow>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow window;

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
            return window(owner);
          }
        });
    ApplicationController controller = window(set);
    window = windows.get(0);
    controller.documents().open(List.of(file("first.txt"), file("second.txt")));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == 2;
              }
            }));
  }

  private ApplicationController window(WindowSet owner) {
    MainWindow created = new MainWindow(display, Palette.LIGHT);
    windows.add(created);
    ApplicationController controller = new ApplicationController(owner, created);
    created.attach(controller);
    created.place(new WindowGeometry.Request(0, 0, 620, 420), null);
    created.open();
    return controller;
  }

  private Path file(String name) throws Exception {
    return Files.writeString(temp.resolve(name), "alpha\nbeta\n", StandardCharsets.US_ASCII);
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (MainWindow each : windows) {
              if (!each.shell().isDisposed()) {
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

  private static Event mouse(CTabFolder tabs, int type, int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    tabs.notifyListeners(type, event);
    return event;
  }

  private void dragOff(Control under) {
    final CTabFolder tabs = window.tabs();
    window.setPointerProbe(
        new MainWindow.PointerProbe() {
          @Override
          public Control controlAt(Point point) {
            return under;
          }
        });
    Rectangle from = tabs.getItem(0).getBounds();
    mouse(tabs, SWT.MouseDown, from.x + from.width / 2, from.y + from.height / 2);
    Rectangle client = tabs.getClientArea();
    mouse(tabs, SWT.MouseUp, client.x + client.width / 2, client.y + client.height / 2);
  }

  @Test
  void theSelectedTabsCloseButtonClosesIt() {
    final CTabFolder tabs = window.tabs();
    assertEquals(1, tabs.getSelectionIndex());
    String kept = tabs.getItem(0).getText();
    Rectangle bounds = tabs.getItem(1).getBounds();
    int y = bounds.y + bounds.height / 2;
    // The close button sits at the item's right end; press and release along the item until it
    // closes.
    for (int x = bounds.x + bounds.width - 1; x > bounds.x && tabs.getItemCount() == 2; x--) {
      mouse(tabs, SWT.MouseDown, x, y);
      mouse(tabs, SWT.MouseUp, x, y);
      while (display.readAndDispatch()) {
        // Runs the close.
      }
    }
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getItemCount() == 1;
              }
            }));
    assertEquals(kept, tabs.getItem(0).getText());
  }

  @Test
  void aMenuRequestOnATabTakesTheEventAndOffTheTabsLeavesIt() {
    // The request opens the tab menu; cocoa runs a shown popup in a modal loop nothing closes.
    assumeFalse("cocoa".equals(SWT.getPlatform()), "cocoa runs the tab menu in a modal loop");
    CTabFolder tabs = window.tabs();
    Rectangle item = tabs.getItem(0).getBounds();
    Event onTab = new Event();
    Point at = tabs.toDisplay(item.x + item.width / 2, item.y + item.height / 2);
    onTab.x = at.x;
    onTab.y = at.y;
    tabs.notifyListeners(SWT.MenuDetect, onTab);
    assertFalse(onTab.doit);
    Event offTabs = new Event();
    Rectangle client = tabs.getClientArea();
    Point below = tabs.toDisplay(client.x + client.width / 2, client.y + client.height / 2);
    offTabs.x = below.x;
    offTabs.y = below.y;
    tabs.notifyListeners(SWT.MenuDetect, offTabs);
    assertTrue(offTabs.doit);
    assertEquals(2, tabs.getItemCount());
  }

  @Test
  void aDragReleasedOverItsOwnWindowOrADialogMovesNothing() {
    String first = window.tabs().getItem(0).getText();
    dragOff(window.statusBar());
    Shell dialog = new Shell(window.shell(), SWT.DIALOG_TRIM);
    try {
      dragOff(dialog);
    } finally {
      dialog.dispose();
    }
    while (display.readAndDispatch()) {
      // Runs anything the releases posted.
    }
    assertEquals(1, windows.size());
    assertEquals(2, window.tabs().getItemCount());
    assertEquals(first, window.tabs().getItem(0).getText());
  }
}
