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
import io.github.ajdailey.qcaret.app.config.MarkdownOpenMode;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.platform.RunningPlatform;
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
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
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
 * the renderer, a theme switch recolors the pane, an editor scroll moves the preview in Split and
 * with Sync Scrolling off neither side moves the other, Ctrl+A selects only the focused side and
 * Copy then copies the preview's text, the Browser menu item names its reason when disabled, View >
 * Markdown View holds the modes and the renderers, the cycle key runs from the preview, Find from
 * Preview selects in the hidden source and returns the focus to the preview, and a match found in
 * Preview scrolls into view selected. With {@code -Dqcaret.rm.out=<dir>} that match and the preview
 * selected by Ctrl+A are saved there as PNG, light and dark.
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
            // These windows start each Markdown file in Source and switch from there.
            Preferences.DEFAULTS.withOptions(
                Options.DEFAULTS.withMarkdownOpenMode(MarkdownOpenMode.SOURCE)),
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
    } finally {
      display.dispose();
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

  /** Runs the event loop for 1 s, past the paint that reports a scroll and the follow it starts. */
  private void settle() {
    final long until = System.nanoTime() + 1_000_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
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

  /**
   * A real click: SWT checks a radio tool item after sending a Selection event to the item it
   * unchecks, and a listener that synced the items on that event unchecked the clicked one.
   */
  @Test
  void aClickOnASwitchItemRunsItsMode() throws Exception {
    PostedInput.assumeGtk();
    openFile("guide.md", "# Mock guide\n\nBody text.\n");
    awaitMarkdown(true);
    window.shell().forceActive();
    flush();
    ToolItem[] items = window.markdownSwitch().items();
    PostedInput.click(display, center(items[1]));
    awaitMode(MarkdownViewMode.SPLIT);
    PostedInput.click(display, center(items[2]));
    awaitMode(MarkdownViewMode.PREVIEW);
    assertFalse(items[0].getSelection());
    assertFalse(items[1].getSelection());
    assertTrue(items[2].getSelection());
  }

  private static Point center(ToolItem item) {
    Rectangle bounds = item.getBounds();
    return item.getParent().toDisplay(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  }

  private void awaitMode(final MarkdownViewMode mode) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().markdownMode() == mode;
          }
        },
        "mode " + mode + ", was " + controller.documents().markdownMode());
    flush();
  }

  /** On GTK 4 the focused editor sees a menu accelerator's key: Ctrl+Shift+M carries a CR. */
  @Test
  void theCycleKeyFromTheEditorLeavesTheDocumentUnchanged() throws Exception {
    PostedInput.assumeGtk();
    String source = "# Mock guide\n\nBody text.\n";
    openFile("guide.md", source);
    awaitMarkdown(true);
    window.shell().forceActive();
    window.activeEditor().text().setFocus();
    window.activeEditor().text().setCaretOffset(5);
    flush();
    PostedInput.ctrlShift(display, 'm');
    awaitMode(MarkdownViewMode.SPLIT);
    PostedInput.ctrlShift(display, 'm');
    awaitMode(MarkdownViewMode.PREVIEW);
    assertEquals(source, controller.documents().activeEditor().document().text().toString());
  }

  /**
   * Preview paints the pane alone: the frame prints as the pane does. On GTK 4 the editors that
   * SashForm moved off the frame painted over the pane's top left.
   */
  @Test
  void previewShowsThePaneAlone() throws Exception {
    StringBuilder text = new StringBuilder("# Mock\n\n");
    for (int i = 0; i < 80; i++) {
      text.append("<!-- mock editor line ").append(i).append(" -->\n");
    }
    openFile("guide.md", text.toString());
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitShown("Mock");
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    flush();
    Control pane = window.activePreview().control();
    assertEquals(frame().getSize(), pane.getSize());
    ImageData framePixels = ControlPrint.of(display, frame());
    ImageData panePixels = ControlPrint.of(display, pane);
    assumeTrue(framePixels != null && panePixels != null, "Control.print failed");
    // The pane's caret blinks between the two prints; its box is left out.
    Rectangle caret = window.activePreview().text().getCaret().getBounds();
    int differing = 0;
    for (int y = 0; y < panePixels.height; y++) {
      for (int x = 0; x < panePixels.width; x++) {
        if (caret.contains(x, y)) {
          continue;
        }
        RGB seen = framePixels.palette.getRGB(framePixels.getPixel(x, y));
        if (!seen.equals(panePixels.palette.getRGB(panePixels.getPixel(x, y)))) {
          differing++;
        }
      }
    }
    assertEquals(0, differing, "frame pixels that differ from the pane's");
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

  /** With Sync Scrolling off neither side of Split moves the other; on, both follow again. */
  @Test
  void syncScrollingOffLeavesEachSideWhereItIs() throws Exception {
    StringBuilder text = new StringBuilder("# Top\n\n");
    for (int i = 0; i < 300; i++) {
      text.append("Paragraph ").append(i).append(".\n\n");
    }
    openFile("long.md", text.toString());
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitShown("Paragraph 299");
    final MarkdownPane pane = window.activePreview();
    final EditorPane editor = window.activeEditor();
    controller.execute(CommandId.MARKDOWN_SYNC_SCROLL);
    window.menuBar().syncAll();
    assertFalse(window.menuBar().item(CommandId.MARKDOWN_SYNC_SCROLL).getSelection());
    editor.scrollToRow(200);
    settle();
    assertEquals(0, pane.text().getTopIndex());
    final int editorTop = editor.topRow();
    pane.text().setTopIndex(400);
    settle();
    assertEquals(400, pane.text().getTopIndex());
    assertEquals(editorTop, editor.topRow());
    controller.execute(CommandId.MARKDOWN_SYNC_SCROLL);
    pane.text().setTopIndex(100);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.topRow() != editorTop;
          }
        },
        "editor followed the preview, top " + editor.topRow());
    editor.scrollToRow(500);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.text().getTopIndex() > 300;
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
    if (!RunningPlatform.GTK4) {
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
        !RunningPlatform.GTK4
            ? "B&rowser"
            : UiText.MENU_ITEM_REASON.format("B&rowser", UiText.MARKDOWN_BROWSER_GTK4.text());
    assertEquals(
        List.of(
            cycle,
            "&Source",
            "S&plit",
            "Pre&view",
            "-",
            "&Basic",
            browser,
            "-",
            "S&ync Scrolling",
            "&Outline"),
        labels);
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

  @Test
  void ctrlAInThePreviewSelectsItsTextAndCtrlCCopiesIt() throws Exception {
    PostedInput.assumeGtk();
    openFile("guide.md", "# Mock guide\n\nBody text.\n");
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitShown("Body text.");
    final StyledText text = window.activePreview().text();
    window.shell().forceActive();
    text.setFocus();
    flush();
    PostedInput.ctrl(display, 'a');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.getSelectionCount() == text.getCharCount();
          }
        },
        "Ctrl+A selects the preview, selection " + text.getSelection());
    final Clipboard clipboard = new Clipboard(display);
    try {
      clipboard.setContents(new Object[] {"mock"}, new Transfer[] {TextTransfer.getInstance()});
      PostedInput.ctrl(display, 'c');
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              Object copied = clipboard.getContents(TextTransfer.getInstance());
              return copied instanceof String s
                  && s.startsWith("Mock guide")
                  && s.contains("Body text.");
            }
          },
          "Ctrl+C copies the preview");
    } finally {
      clipboard.dispose();
    }
    String out = System.getProperty("qcaret.rm.out");
    if (out != null) {
      shot(Path.of(out, "select-all-preview-light.png"));
      controller.execute(CommandId.THEME_DARK);
      flush();
      shot(Path.of(out, "select-all-preview-dark.png"));
    }
  }

  @Test
  void ctrlAInTheEditorOfSplitLeavesThePreviewSelectionAlone() throws Exception {
    PostedInput.assumeGtk();
    final String source = "# Mock guide\n\nBody text.\n";
    openFile("guide.md", source);
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitShown("Body text.");
    StyledText preview = window.activePreview().text();
    preview.setSelection(0, 4);
    window.shell().forceActive();
    window.activeEditor().text().setFocus();
    flush();
    PostedInput.ctrl(display, 'a');
    final EditorController editor = controller.documents().activeEditor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Selection s = editor.selections().selections().primary();
            return s.start() == 0 && s.end() == source.length();
          }
        },
        "Ctrl+A selects the source");
    settle();
    assertEquals(new Point(0, 4), preview.getSelection());
  }

  @Test
  void findFromPreviewSelectsInTheSourceAndEscFocusesThePreview() throws Exception {
    openFile("guide.md", "# Mock guide\n\nA needle here.\n");
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitShown("needle");
    window.shell().forceActive();
    window.activePreview().text().setFocus();
    flush();
    controller.execute(CommandId.FIND);
    flush();
    FindBar bar = window.findBar();
    assertTrue(bar.control().isVisible());
    bar.field().setText("needle");
    final EditorController editor = controller.documents().activeEditor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Selection s = editor.selections().selections().primary();
            return s.start() == 16 && s.end() == 22;
          }
        },
        "the source's match selected");
    assertEquals(MarkdownViewMode.PREVIEW, controller.documents().markdownMode());
    assertSame(window.activePreview().control(), frame().getMaximizedControl());
    Event escape = new Event();
    escape.keyCode = SWT.ESC;
    bar.field().notifyListeners(SWT.KeyDown, escape);
    flush();
    assertFalse(bar.control().isVisible());
    assertSame(window.activePreview().text(), display.getFocusControl());
  }

  @Test
  void findInPreviewScrollsTheMatchIntoViewAndSelectsIt() throws Exception {
    StringBuilder text = new StringBuilder("# Top\n\n");
    for (int i = 0; i < 300; i++) {
      text.append(i == 250 ? "Paragraph with a **needle** in it." : "Paragraph " + i + ".");
      text.append("\n\n");
    }
    openFile("long.md", text.toString());
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitShown("Paragraph 299");
    window.shell().forceActive();
    final MarkdownPane pane = window.activePreview();
    pane.text().setFocus();
    flush();
    controller.execute(CommandId.FIND);
    flush();
    FindBar bar = window.findBar();
    bar.field().setText("needle");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "needle".equals(pane.text().getSelectionText());
          }
        },
        "the rendered match selected, was '" + pane.text().getSelectionText() + "'");
    flush();
    int y = pane.text().getLocationAtOffset(pane.text().getSelection().x).y;
    assertTrue(
        y >= 0 && y < pane.text().getClientArea().height,
        "match at y " + y + ", top " + pane.text().getTopIndex());
    assertSame(bar.field(), display.getFocusControl());
    String out = System.getProperty("qcaret.rm.out");
    if (out != null) {
      shot(Path.of(out, "find-preview-light.png"));
      controller.execute(CommandId.THEME_DARK);
      flush();
      shot(Path.of(out, "find-preview-dark.png"));
    }
    Event escape = new Event();
    escape.keyCode = SWT.ESC;
    bar.field().notifyListeners(SWT.KeyDown, escape);
    flush();
    assertSame(pane.text(), display.getFocusControl());
  }

  /**
   * A document whose paragraphs sit between tables, code blocks and headings, so rendered lines
   * differ in height and the preview's line spacing adds up.
   */
  private static String mixedLines() {
    StringBuilder text = new StringBuilder("# Top\n\n");
    for (int i = 0; i < 300; i++) {
      if (i % 40 == 0) {
        text.append("## Section ").append(i).append("\n\n");
        text.append("| Head | `code` |\n|---|---|\n| a | **b** |\n\n");
        text.append("```\nmock code\n```\n\n");
      }
      text.append(i == 250 ? "Paragraph with a **needle** in it." : "Paragraph " + i + ".");
      text.append("\n\n");
    }
    return text.toString();
  }

  /** Returns the zero-based source line holding a text. */
  private static int sourceLine(String source, String text) {
    String[] lines = source.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(text)) {
        return i;
      }
    }
    throw new AssertionError(text);
  }

  /** Sync Scrolling lands each side on the mapped line with the preview's line spacing. */
  @Test
  void syncScrollingLandsOnTheMappedLineWithLineSpacing() throws Exception {
    String source = mixedLines();
    final RenderedMarkdown r = new MarkdownRenderer().render(source, temp, new CancellationToken());
    openFile("long.md", source);
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitShown("Paragraph 299");
    final MarkdownPane pane = window.activePreview();
    final EditorPane editor = window.activeEditor();
    assertEquals(2, pane.text().getLineSpacing());
    final int row = sourceLine(source, "Paragraph 200.");
    editor.scrollToRow(row);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.text().getTopIndex() == r.renderedLine(row);
          }
        },
        "preview at line " + r.renderedLine(row) + ", top " + pane.text().getTopIndex());
    assertEquals(0, pane.text().getLinePixel(pane.text().getTopIndex()));
    final int line = r.renderedLine(sourceLine(source, "Paragraph 100."));
    pane.text().setTopIndex(line);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.topRow() == r.sourceLine(line);
          }
        },
        "editor at row " + r.sourceLine(line) + ", top " + editor.topRow());
  }

  /** Find in Preview puts the match's line at the top with the preview's line spacing. */
  @Test
  void findInPreviewLandsOnTheMatchLineWithLineSpacing() throws Exception {
    String source = mixedLines();
    openFile("long.md", source);
    awaitMarkdown(true);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitShown("Paragraph 299");
    window.shell().forceActive();
    final MarkdownPane pane = window.activePreview();
    pane.text().setFocus();
    flush();
    controller.execute(CommandId.FIND);
    flush();
    window.findBar().field().setText("needle");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "needle".equals(pane.text().getSelectionText());
          }
        },
        "the rendered match selected, was '" + pane.text().getSelectionText() + "'");
    flush();
    int line = pane.text().getLineAtOffset(pane.text().getSelection().x);
    assertEquals(line, pane.text().getTopIndex());
    assertEquals(0, pane.text().getLinePixel(line));
  }

  /** Saves the window as the screen shows it, after 2 s of event loop for the repaint. */
  private void shot(Path file) throws Exception {
    final long until = System.nanoTime() + 2_000_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
    Files.createDirectories(file.getParent());
    Rectangle bounds = window.shell().getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(file.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
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
