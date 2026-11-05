package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Syntax colors in a real window: a Java file paints its keyword in the palette's keyword style,
 * the View menu's Syntax cascade lists and switches the grammar, and a find match keeps the token
 * color over its background.
 */
@Tag("swt")
class SyntaxPaneTest {
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
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    controller.loadGrammars();
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
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
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private static RGB rgb(TokenRole role) {
    Rgb color = Palette.LIGHT.style(role).foreground();
    return new RGB(color.red(), color.green(), color.blue());
  }

  /** Returns the style covering a display offset of row 0, or null. */
  private static StyleRange styleAt(EditorPane pane, int offset) {
    for (StyleRange style : pane.rowStyles(0)) {
      if (offset >= style.start && offset < style.start + style.length) {
        return style;
      }
    }
    return null;
  }

  private static Menu cascade(Menu menu, String text) {
    MenuItem holder = item(menu, text);
    return holder == null ? null : holder.getMenu();
  }

  private static MenuItem item(Menu menu, String text) {
    for (MenuItem item : menu.getItems()) {
      if (text.equals(item.getText())) {
        return item;
      }
    }
    return null;
  }

  @Test
  void aJavaKeywordPaintsInTheKeywordStyleAndTheSyntaxMenuSwitchesIt() throws Exception {
    Path file =
        Files.writeString(
            temp.resolve("Mock.java"),
            "public class Mock { int n = 42; }\n",
            StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorPane pane = window.activeEditor();
            StyleRange style = pane == null ? null : styleAt(pane, 0);
            return style != null && style.foreground != null;
          }
        },
        "keyword styled");
    EditorPane pane = window.activeEditor();
    StyleRange keyword = styleAt(pane, 0);
    assertEquals(rgb(TokenRole.KEYWORD), keyword.foreground.getRGB());
    assertEquals(SWT.BOLD, keyword.fontStyle & SWT.BOLD);
    assertEquals(rgb(TokenRole.NUMBER), styleAt(pane, 28).foreground.getRGB());

    controller.find().open(false);
    controller.find().setText("42");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            StyleRange style = styleAt(window.activeEditor(), 28);
            return style != null && style.background != null;
          }
        },
        "match painted");
    assertEquals(rgb(TokenRole.NUMBER), styleAt(pane, 28).foreground.getRGB());
    controller.find().close();

    Menu view = window.menuBar().menu(CommandGroup.VIEW);
    view.notifyListeners(SWT.Show, null);
    Menu syntax = window.menuBar().item(CommandId.RELOAD_GRAMMARS).getParent();
    MenuItem plain = syntax.getItem(0);
    assertEquals("Plain Text", plain.getText());
    MenuItem java = item(cascade(syntax, "&Programming Languages A-M"), "Java");
    assertTrue(java != null && java.getSelection());
    MenuItem python = item(cascade(syntax, "Programming Languages &N-Z"), "Python");
    python.setSelection(true);
    python.notifyListeners(SWT.Selection, null);
    view.notifyListeners(SWT.Show, null);
    syntax = window.menuBar().item(CommandId.RELOAD_GRAMMARS).getParent();
    assertEquals("Python", syntax.getItem(0).getText());
    assertTrue(syntax.getItem(0).getSelection());
    assertEquals(SWT.SEPARATOR, syntax.getItem(1).getStyle() & SWT.SEPARATOR);
    plain = syntax.getItem(2);
    assertEquals("Plain Text", plain.getText());
    plain.setSelection(true);
    plain.notifyListeners(SWT.Selection, null);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return styleAt(window.activeEditor(), 0) == null;
          }
        },
        "plain text has no styles");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.statusText().endsWith("Plain Text");
          }
        },
        "status shows Plain Text: " + window.statusText());
  }
}
