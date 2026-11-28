package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
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
 * The Preferences key field takes a menu item's key: while it has focus no menu bar item holds an
 * accelerator, and a posted Ctrl+S shows in the field instead of saving. On macOS the menu bar took
 * Cmd+S before the field saw it, so File > Save ran and the key could not be entered.
 */
@Tag("swt")
class KeyCaptureSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Keymap keymap;
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
    keymap =
        Keymap.defaults(PlatformTraits.PLATFORM.macKeys(), PlatformTraits.PLATFORM.linuxKeys());
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            keymap,
            false);
    window.attach(controller);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      workers.close();
    } finally {
      display.dispose();
      assertNull(failure.get());
    }
  }

  @Test
  void theFocusedKeyFieldHoldsEveryMenuKey() {
    MenuItem save = saveItem();
    int accelerator = save.getAccelerator();
    assertNotEquals(0, accelerator, "File > Save has its key");
    PreferencesDialog dialog = dialog();
    Text field = focusKeyField(dialog);
    assertEquals(List.of(), menuKeys(), "menu items with a key while the field has focus");
    final Text filter = editable(field.getParent().getParent());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            filter.setFocus();
            return !field.isFocusControl();
          }
        },
        "focus left the field");
    assertEquals(accelerator, save.getAccelerator(), "File > Save's key after focus left");
    focusKeyField(dialog);
    dialog.shell().dispose();
    assertEquals(accelerator, save.getAccelerator(), "File > Save's key after the dialog closed");
  }

  @Test
  void aPostedMenuKeyShowsInTheKeyFieldAndDoesNotRunItsCommand() throws Exception {
    String platform = SWT.getPlatform();
    assumeTrue(
        "gtk".equals(platform) || "win32".equals(platform),
        "keys posted through the platform: GTK and win32");
    Path file = Files.writeString(temp.resolve("notes.txt"), "mock\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeEditor() != null
                && "mock\n"
                    .equals(controller.documents().activeEditor().document().text().toString());
          }
        },
        "file open");
    controller.documents().activeEditor().type("edited ");
    PreferencesDialog dialog = dialog();
    final Text field = focusKeyField(dialog);
    assumeTrue(
        "1".equals(System.getenv("SWT_GTK4")) || postedKeysArrive(),
        "posted keys do not reach this desktop");
    final String shown = keymap.keyText(new KeyStroke(KeyStroke.PRIMARY, 's'));
    PostedInput.ctrl(display, 's');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.equals(field.getText());
          }
        },
        "Ctrl+S in the key field: " + field.getText());
    drain();
    assertEquals("mock\n", Files.readString(file, StandardCharsets.UTF_8), "the file not saved");
  }

  private MenuItem saveItem() {
    int wanted = SwtKeys.accelerator(keymap.accelerator(CommandId.SAVE));
    for (MenuItem item : items(window.shell().getMenuBar(), new ArrayList<MenuItem>())) {
      if (item.getAccelerator() == wanted) {
        return item;
      }
    }
    Menu bar = display.getMenuBar();
    for (MenuItem item : items(bar, new ArrayList<MenuItem>())) {
      if (item.getAccelerator() == wanted) {
        return item;
      }
    }
    throw new AssertionError("no menu item holds File > Save's key");
  }

  /** Returns the menu bar items that hold a key, as their texts. */
  private List<String> menuKeys() {
    List<MenuItem> all = items(display.getMenuBar(), new ArrayList<MenuItem>());
    for (Shell shell : display.getShells()) {
      items(shell.getMenuBar(), all);
    }
    List<String> keyed = new ArrayList<String>();
    for (MenuItem item : all) {
      if (item.getAccelerator() != 0) {
        keyed.add(item.getText());
      }
    }
    return keyed;
  }

  private static List<MenuItem> items(Menu menu, List<MenuItem> found) {
    if (menu == null) {
      return found;
    }
    for (MenuItem item : menu.getItems()) {
      found.add(item);
      items(item.getMenu(), found);
    }
    return found;
  }

  private PreferencesDialog dialog() {
    controller.execute(CommandId.PREFERENCES);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.preferencesDialog() != null
                && !window.preferencesDialog().shell().isDisposed();
          }
        },
        "Preferences shown");
    return window.preferencesDialog();
  }

  /** Shows the Keys tab and focuses its key field; skips where the desktop refuses activation. */
  private Text focusKeyField(PreferencesDialog dialog) {
    final Text field = keyField(dialog.shell());
    assertNotNull(field, "the key field");
    for (int i = 0; i < dialog.folder().count(); i++) {
      if (contains(dialog.folder().page(i), field)) {
        dialog.folder().select(i);
        break;
      }
    }
    dialog.shell().forceActive();
    field.setFocus();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            if (!field.isFocusControl()) {
              field.setFocus();
            }
            return field.isFocusControl();
          }
        },
        BOUND);
    assumeTrue(field.isFocusControl(), "the key field cannot take focus on this desktop");
    drain();
    return field;
  }

  private static Text keyField(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Text text
          && UiText.PREFERENCES_KEY_CAPTURE.text().equals(text.getMessage())) {
        return text;
      }
      if (child instanceof Composite composite) {
        Text found = keyField(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static boolean contains(Composite parent, Control control) {
    for (Control at = control; at != null; at = at.getParent()) {
      if (at == parent) {
        return true;
      }
    }
    return false;
  }

  /** Returns the first editable text field, the Keys tab's filter. */
  private static Text editable(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Text text && (text.getStyle() & SWT.READ_ONLY) == 0) {
        return text;
      }
      if (child instanceof Composite composite) {
        Text found = editable(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
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
      Event down = new Event();
      down.type = SWT.KeyDown;
      down.keyCode = SWT.SHIFT;
      Event up = new Event();
      up.type = SWT.KeyUp;
      up.keyCode = SWT.SHIFT;
      if (!display.post(down) || !display.post(up)) {
        return false;
      }
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

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }
}
