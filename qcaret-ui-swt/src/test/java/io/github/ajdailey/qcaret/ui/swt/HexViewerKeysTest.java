package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
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
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window keys of a Hex Viewer window over a main window with a text tab. The main menus on the
 * viewer, built through {@code CocoaMenuBehavior} on any host, run Select All, Copy, Find, Find
 * Next, Go to Line and Close on the viewer, each item holding its key's accelerator, since on cocoa
 * a menu accelerator fires before the key reaches the focused control. On GTK 3 and win32 posted
 * Ctrl+F, Ctrl+G and Ctrl+W reach the active viewer; GTK 4 does not implement posted keys and cocoa
 * needs an accessibility grant.
 */
@Tag("swt")
class HexViewerKeysTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final byte[] BYTES = {
    (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 1
  };

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private HexWindow viewer;
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
    window.place(new WindowGeometry.Request(null, null, 900, 500), null);
    window.open();
    Path text =
        Files.writeString(temp.resolve("main.txt"), "PNG main text\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(text));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "the text tab");
    viewer = openViewer();
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

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  /** Opens the binary file through the prompt's Open in Hex Viewer and waits for its bytes. */
  private HexWindow openViewer() throws Exception {
    Path file = temp.resolve("blob.bin");
    Files.write(file, BYTES);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return prompt() != null;
          }
        },
        "the binary prompt");
    prompt().getDefaultButton().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<HexWindow> windows = window.hexViews().windows();
            return windows.size() == 1 && windows.get(0).pane().rows().size() == BYTES.length;
          }
        },
        "the Hex Viewer");
    drain();
    return window.hexViews().windows().get(0);
  }

  private Shell prompt() {
    for (Shell child : window.shell().getShells()) {
      if (!child.isDisposed() && child.getText().equals(UiText.BINARY_TITLE.text())) {
        return child;
      }
    }
    return null;
  }

  @Test
  void theMainMenusOnAHexViewerRunItsKeysOnTheViewer() {
    if (viewer.shell().getMenuBar() == null) {
      PlatformTraits.of("cocoa", false)
          .menuBehavior()
          .toolWindowMenus(viewer.shell(), ToolMenuBars.of(window.shell()));
    }
    Menu bar = viewer.shell().getMenuBar();
    assertNotNull(bar);
    viewer.shell().forceActive();
    drain();

    select(bar, CommandId.FIND);
    assertTrue(viewer.pane().findBar().getVisible(), "the viewer's find bar");
    assertFalse(window.findBar().control().getVisible(), "the main window's find bar");
    hexPattern("50 4E 47");
    select(bar, CommandId.FIND_NEXT);
    awaitCopy(bar, "50 4E 47");

    select(bar, CommandId.SELECT_ALL);
    awaitCopy(bar, "89 50 4E 47 00 00 00 0D 49 48 44 52 00 01");
    assertEquals(0, window.activeText().getSelectionCount(), "the main editor's selection");

    select(bar, CommandId.GO_TO_LINE);
    assertNotNull(dialog(viewer.shell(), UiText.HEX_GO_TO_OFFSET_TITLE.text()), "Go to Offset");
    assertNull(dialog(window.shell(), UiText.GO_TO_TITLE.text()), "the main window's Go to Line");
    dialog(viewer.shell(), UiText.HEX_GO_TO_OFFSET_TITLE.text()).dispose();

    select(bar, CommandId.CLOSE);
    drain();
    assertTrue(viewer.shell().isDisposed());
    assertEquals(1, window.tabs().getItemCount());
  }

  /**
   * Checks that a command's item is enabled and holds its key's accelerator, then selects it as the
   * platform does for the key or a click.
   */
  private void select(Menu bar, CommandId command) {
    MenuItem item = item(bar, command);
    assertNotNull(item, command.name());
    KeyStroke key = controller.keymap().accelerator(command);
    if (key != null) {
      assertEquals(SwtKeys.accelerator(key), item.getAccelerator(), command.name());
    }
    assertTrue(item.getEnabled(), command.name() + " enabled");
    item.notifyListeners(SWT.Selection, new Event());
    drain();
  }

  private static MenuItem item(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
      if (item.getMenu() != null) {
        MenuItem found = item(item.getMenu(), command);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** Puts a hex pattern in the viewer's find bar with Hex bytes checked. */
  private void hexPattern(String pattern) {
    int set = 0;
    for (Control child : viewer.pane().findBar().getChildren()) {
      if (child instanceof Text text) {
        text.setText(pattern);
        set++;
      } else if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
        button.setSelection(true);
        set++;
      }
    }
    assertEquals(2, set, "the find field and Hex bytes");
  }

  private static Shell dialog(Shell owner, String title) {
    for (Shell child : owner.getShells()) {
      if (!child.isDisposed() && child.getText().equals(title)) {
        return child;
      }
    }
    return null;
  }

  /** Selects Copy until the clipboard holds a text; a search selects its match on a worker. */
  private void awaitCopy(final Menu bar, final String expected) {
    final AtomicReference<String> seen = new AtomicReference<String>();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            select(bar, CommandId.COPY);
            seen.set(clipboard());
            return expected.equals(seen.get());
          }
        },
        BOUND);
    assertEquals(
        expected,
        seen.get(),
        viewer.pane().status().getText() + " / " + viewer.pane().findMessage().getText());
  }

  private String clipboard() {
    Clipboard clipboard = new Clipboard(display);
    try {
      return (String) clipboard.getContents(TextTransfer.getInstance());
    } finally {
      clipboard.dispose();
    }
  }

  @Test
  void postedKeysReachTheActiveViewer() {
    String platform = SWT.getPlatform();
    assumeTrue(
        ("gtk".equals(platform) && !"1".equals(System.getenv("SWT_GTK4")))
            || "win32".equals(platform),
        "keys posted through Display.post: GTK 3 and win32 only");
    activateViewer();
    assumeTrue(postedKeysArrive(), "posted keys do not reach this desktop");

    ctrl('f');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return viewer.pane().findBar().getVisible();
          }
        },
        "Ctrl+F opens the viewer's find bar");
    assertFalse(window.findBar().control().getVisible(), "the main window's find bar");

    activateViewer();
    ctrl('g');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dialog(viewer.shell(), UiText.HEX_GO_TO_OFFSET_TITLE.text()) != null;
          }
        },
        "Ctrl+G opens Go to Offset");
    assertNull(dialog(window.shell(), UiText.GO_TO_TITLE.text()), "the main window's Go to Line");
    dialog(viewer.shell(), UiText.HEX_GO_TO_OFFSET_TITLE.text()).dispose();

    activateViewer();
    ctrl('w');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return viewer.shell().isDisposed();
          }
        },
        "Ctrl+W closes the viewer");
    drain();
    assertEquals(1, window.tabs().getItemCount());
  }

  /** Activates the viewer with its rows focused; skips the test where the desktop refuses. */
  private void activateViewer() {
    viewer.shell().forceActive();
    viewer.pane().focus();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return display.getActiveShell() == viewer.shell()
                && viewer.pane().canvas().isFocusControl();
          }
        },
        Duration.ofSeconds(5));
    assumeTrue(
        display.getActiveShell() == viewer.shell() && viewer.pane().canvas().isFocusControl(),
        "the viewer is not the foreground window");
  }

  /** Posts a Shift press and reports whether its KeyDown reached the display. */
  private boolean postedKeysArrive() {
    final AtomicBoolean seen = new AtomicBoolean();
    Listener probe =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.SHIFT) {
              seen.set(true);
            }
          }
        };
    display.addFilter(SWT.KeyDown, probe);
    try {
      post(SWT.KeyDown, SWT.SHIFT, 0);
      post(SWT.KeyUp, SWT.SHIFT, 0);
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return seen.get();
            }
          },
          Duration.ofSeconds(5));
    } finally {
      display.removeFilter(SWT.KeyDown, probe);
    }
    return seen.get();
  }

  private void ctrl(char letter) {
    post(SWT.KeyDown, SWT.CTRL, 0);
    post(SWT.KeyDown, 0, letter);
    post(SWT.KeyUp, 0, letter);
    post(SWT.KeyUp, SWT.CTRL, 0);
    drain();
  }

  private void post(int type, int keyCode, int character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = (char) character;
    assertTrue(display.post(event));
  }
}
