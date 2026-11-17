package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab, Shift+Tab and Ctrl+Tab posted through {@code Display.post} to a focused editor, so each key
 * takes the native traversal path. Runs on GTK 3 and win32 when the test window is the foreground
 * window; GTK 4 does not implement posted keys and cocoa needs an accessibility grant.
 */
@Tag("swt")
class EditorTabKeysTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private StyledText text;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
    String platform = SWT.getPlatform();
    assumeTrue(
        ("gtk".equals(platform) && !"1".equals(System.getenv("SWT_GTK4")))
            || "win32".equals(platform),
        "keys posted through Display.post: GTK 3 and win32 only");
    Files.createDirectories(temp.resolve("conf"));
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
    ConfigStore config =
        new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf")));
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            config,
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
    Path file = temp.resolve("tabs.txt");
    Files.writeString(file, "aa\nbb\ncc\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "open");
    text = window.activeText();
    window.shell().forceActive();
    text.setFocus();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return display.getActiveShell() == window.shell() && text.isFocusControl();
          }
        },
        Duration.ofSeconds(5));
    assumeTrue(
        display.getActiveShell() == window.shell() && text.isFocusControl(),
        "the test window is not the foreground window");
  }

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  @Test
  void tabIndentsTheSelectedLinesAndKeepsFocus() {
    controller.documents().activeEditor().selectFromView(0, 5);
    tab(0);
    awaitText("\taa\n\tbb\ncc\n", "Tab indents lines 1 and 2");
    assertTrue(text.isFocusControl());
  }

  @Test
  void shiftTabOutdentsTheSelectedLinesAndKeepsFocus() {
    controller.documents().activeEditor().selectFromView(0, 0);
    controller.documents().activeEditor().type("\t");
    controller.documents().activeEditor().selectFromView(0, 6);
    drain();
    assertEquals("\taa\nbb\ncc\n", text.getText());
    tab(SWT.SHIFT);
    awaitText("aa\nbb\ncc\n", "Shift+Tab outdents line 1");
    assertTrue(text.isFocusControl());
  }

  @Test
  void tabAtACaretInsertsATab() {
    controller.documents().activeEditor().selectFromView(3, 3);
    tab(0);
    awaitText("aa\n\tbb\ncc\n", "Tab inserts a tab");
  }

  /** Ctrl+Tab has no editor binding and moves focus out of the editor, as the widget does. */
  @Test
  void ctrlTabLeavesTheEditor() {
    tab(SWT.CTRL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !text.isFocusControl();
          }
        },
        "Ctrl+Tab moves focus");
    assertEquals("aa\nbb\ncc\n", text.getText());
  }

  private void tab(int modifier) {
    if (modifier != 0) {
      post(SWT.KeyDown, modifier, 0);
    }
    post(SWT.KeyDown, SWT.TAB, 0);
    post(SWT.KeyUp, SWT.TAB, 0);
    if (modifier != 0) {
      post(SWT.KeyUp, modifier, 0);
    }
  }

  private void post(int type, int keyCode, int character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = (char) character;
    assertTrue(display.post(event));
  }

  private void awaitText(final String expected, String what) {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return expected.equals(text.getText());
          }
        },
        BOUND);
    assertEquals(expected, text.getText(), what);
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }
}
