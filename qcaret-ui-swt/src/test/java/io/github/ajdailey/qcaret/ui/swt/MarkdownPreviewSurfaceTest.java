package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
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
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPane;
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
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Markdown preview in a real window: the toolbar switch shows for a Markdown tab only, Split
 * stands right of both views of an editor split, Preview hides the editors, the status bar names
 * the renderer, a theme switch recolors the pane, an editor scroll moves the preview in Split, the
 * Browser menu item names its reason when disabled, View > Markdown View holds the modes and the
 * renderers, and the cycle key runs from the preview.
 */
@Tag("swt")
class MarkdownPreviewSurfaceTest {
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
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 900, 600), null);
    window.open();
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars");
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

  private void flush() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void openFile(String name, String text) throws Exception {
    Path file = Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        name);
    flush();
  }

  private void awaitMarkdown(final boolean markdown) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeMarkdown() == markdown;
          }
        },
        "markdown " + markdown);
    flush();
  }

  private void awaitShown(final String text) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            MarkdownPane pane = window.activePreview();
            return pane != null && pane.text().getText().contains(text);
          }
        },
        "preview shows " + text);
    flush();
  }

  private SashForm frame() {
    return (SashForm) window.tabs().getSelection().getControl();
  }

  @Test
  void theSwitchShowsForAMarkdownTabAndRunsTheModes() throws Exception {
    openFile("notes.txt", "plain\n");
    awaitMarkdown(false);
    assertNull(window.markdownSwitch().items()[0]);
    openFile("guide.md", "# Mock guide\n\nBody text.\n");
    awaitMarkdown(true);
    ToolItem[] items = window.markdownSwitch().items();
    assertNotNull(items[0]);
    assertTrue(items[0].getSelection());
    assertEquals("", window.previewField().getText());
    items[1].setSelection(true);
    items[0].setSelection(false);
    items[1].notifyListeners(SWT.Selection, new Event());
    awaitShown("Mock guide");
    assertEquals(MarkdownViewMode.SPLIT, controller.documents().markdownMode());
    assertNull(frame().getMaximizedControl());
    assertEquals(
        UiText.STATUS_PREVIEW.format(UiText.PREVIEW_RENDERER_BASIC.text()),
        window.previewField().getText());
    controller.documents().select(0);
    flush();
    assertNull(window.markdownSwitch().items()[0]);
    assertEquals("", window.previewField().getText());
  }

  @Test
  void splitStandsRightOfBothEditorViewsAndPreviewHidesThem() throws Exception {
    openFile("guide.md", "# Mock guide\n\nBody text.\n");
    awaitMarkdown(true);
    controller.execute(CommandId.SPLIT_VERTICAL);
    flush();
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitShown("Mock guide");
    MarkdownPane pane = window.activePreview();
    List<EditorPane> editors = window.activeEditors();
    assertEquals(2, editors.size());
    int left = pane.control().toDisplay(0, 0).x;
    for (EditorPane editor : editors) {
      Control control = editor.text();
      int x = control.toDisplay(0, 0).x + control.getSize().x;
      assertTrue(x <= left, "editor ends at " + x + ", pane starts at " + left);
    }
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    flush();
    assertSame(pane.control(), frame().getMaximizedControl());
    controller.execute(CommandId.MARKDOWN_SOURCE);
    flush();
    assertSame(frame().getChildren()[0], frame().getMaximizedControl());
  }

  @Test
  void aThemeSwitchRecolorsThePaneAndAnEditorScrollMovesIt() throws Exception {
    StringBuilder text = new StringBuilder("# Top\n\n");
    for (int i = 0; i < 300; i++) {
      text.append("Paragraph ").append(i).append(".\n\n");
    }
    openFile("long.md", text.toString());
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitShown("Paragraph 299");
    final MarkdownPane pane = window.activePreview();
    controller.execute(CommandId.THEME_DARK);
    flush();
    RGB dark =
        new RGB(
            Palette.DARK.background().red(),
            Palette.DARK.background().green(),
            Palette.DARK.background().blue());
    assertEquals(dark, pane.text().getBackground().getRGB());
    window.activeEditor().scrollToRow(200);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.text().getTopIndex() > 100;
          }
        },
        "preview followed the editor, top " + pane.text().getTopIndex());
  }

  @Test
  void theBrowserItemIsEnabledOrNamesItsReason() throws Exception {
    openFile("guide.md", "# Mock\n");
    awaitMarkdown(true);
    MenuItem browser = null;
    for (MenuItem item : window.shell().getMenuBar().getItems()) {
      browser = browser != null ? browser : find(item, CommandId.PREVIEW_BROWSER);
    }
    assertNotNull(browser);
    window.menuBar().syncAll();
    if (PlatformTraits.PLATFORM.opensBrowser()) {
      assertTrue(browser.getEnabled());
      assertNull(browser.getToolTipText());
      assertEquals(CommandId.PREVIEW_BROWSER.label(), browser.getText());
    } else {
      // SWT drops a menu item's tool tip on GTK 4: the label carries the reason.
      assertFalse(browser.getEnabled());
      assertEquals(
          UiText.MENU_ITEM_REASON.format(
              CommandId.PREVIEW_BROWSER.label(), UiText.MARKDOWN_BROWSER_GTK4.text()),
          browser.getText());
    }
  }

  @Test
  void oneMarkdownViewCascadeHoldsTheModesThenTheRenderers() {
    Menu cascade = window.menuBar().item(CommandId.MARKDOWN_SOURCE).getParent();
    List<String> labels = new ArrayList<String>();
    for (MenuItem item : cascade.getItems()) {
      labels.add((item.getStyle() & SWT.SEPARATOR) != 0 ? "-" : item.getText());
    }
    String cycle =
        CommandId.MARKDOWN_CYCLE.label()
            + "\t"
            + Keymap.defaults(PlatformTraits.PLATFORM.macKeys())
                .menuKeyText(CommandId.MARKDOWN_CYCLE);
    String browser =
        PlatformTraits.PLATFORM.opensBrowser()
            ? "B&rowser"
            : UiText.MENU_ITEM_REASON.format("B&rowser", UiText.MARKDOWN_BROWSER_GTK4.text());
    assertEquals(List.of(cycle, "&Source", "S&plit", "Pre&view", "-", "&Basic", browser), labels);
    assertEquals("Markdown V&iew", cascade.getParentItem().getText());
    assertEquals(0, window.menuBar().item(CommandId.MARKDOWN_SPLIT).getAccelerator());
    List<String> view = new ArrayList<String>();
    for (MenuItem item : window.menuBar().menu(CommandGroup.VIEW).getItems()) {
      String text = item.getText();
      int tab = text.indexOf('\t');
      view.add(tab < 0 ? text : text.substring(0, tab));
    }
    for (String label :
        List.of("S&yntax", "Co&mpare", "Markdown V&iew", "&Zoom In", "Memory Indi&cator")) {
      assertTrue(view.contains(label), label + " in " + view);
    }
  }

  @Test
  void theCycleKeyRunsWhileThePreviewHasFocus() throws Exception {
    assumeTrue(
        "gtk".equals(SWT.getPlatform()) && !"1".equals(System.getenv("SWT_GTK4")),
        "keys posted through Display.post: GTK 3 only");
    openFile("guide.md", "# Mock guide\n\nBody text.\n");
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitShown("Mock guide");
    window.shell().forceActive();
    window.activePreview().text().setFocus();
    flush();
    post(SWT.KeyDown, SWT.CTRL, 0);
    post(SWT.KeyDown, SWT.SHIFT, 0);
    post(SWT.KeyDown, 0, 'm');
    post(SWT.KeyUp, 0, 'm');
    post(SWT.KeyUp, SWT.SHIFT, 0);
    post(SWT.KeyUp, SWT.CTRL, 0);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().markdownMode() == MarkdownViewMode.SOURCE;
          }
        },
        "Ctrl+Shift+M in the preview, mode " + controller.documents().markdownMode());
  }

  private void post(int type, int keyCode, int character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = (char) character;
    assertTrue(display.post(event));
  }

  private static MenuItem find(MenuItem item, CommandId command) {
    if (item.getData() == command) {
      return item;
    }
    if (item.getMenu() != null) {
      for (MenuItem child : item.getMenu().getItems()) {
        MenuItem found = find(child, command);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
