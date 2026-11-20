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
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPane;
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
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Outline dock in a real window: it follows tab switches, a click on a heading moves the caret
 * and, in Split, the preview, Enter goes too, the toggle and Hide show and hide it, a theme switch
 * reaches it, and a long heading list fills in slices.
 */
@Tag("swt")
class OutlineSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String GUIDE =
      "# Mock guide\n\nIntro.\n\n## Setup\n\n### Steps\n\n## Usage\n";

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
    window.place(new WindowGeometry.Request(null, null, 1000, 600), null);
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

  private void openFile(String name, String text, final boolean markdown) throws Exception {
    Path file = Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0
                && controller.documents().activeMarkdown() == markdown;
          }
        },
        name);
    flush();
  }

  private void showOutline() {
    controller.execute(CommandId.MARKDOWN_OUTLINE);
    flush();
    assertTrue(window.outlineShown());
  }

  /** Waits until the tree's top items have these names and every item exists. */
  private void awaitTop(final String what, final String... names) {
    final OutlineDock dock = window.outlineDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Tree tree = dock.tree();
            if (!dock.filled() || tree.getItemCount() != names.length) {
              return false;
            }
            for (int i = 0; i < names.length; i++) {
              if (!tree.getItem(i).getText().equals(names[i])) {
                return false;
              }
            }
            return true;
          }
        },
        what);
  }

  private int caretLine() {
    EditorController editor = controller.documents().activeEditor();
    return editor.document().text().lineAt(editor.selections().selections().primary().caret());
  }

  private void click(TreeItem item) {
    Tree tree = item.getParent();
    tree.showItem(item);
    flush();
    Rectangle bounds = item.getBounds();
    Event event = new Event();
    event.button = 1;
    event.x = bounds.x + Math.min(4, bounds.width / 2);
    event.y = bounds.y + bounds.height / 2;
    tree.notifyListeners(SWT.MouseUp, event);
    flush();
  }

  @Test
  void theDockFollowsTabSwitches() throws Exception {
    openFile("guide.md", GUIDE, true);
    showOutline();
    awaitTop("the guide's tree", "Mock guide");
    TreeItem top = window.outlineDock().tree().getItem(0);
    assertTrue(top.getExpanded());
    assertEquals(2, top.getItemCount());
    assertEquals("Setup", top.getItem(0).getText());
    assertEquals("Steps", top.getItem(0).getItem(0).getText());
    assertEquals("guide.md: 4 headings", window.outlineDock().summary().getText());

    openFile("notes.txt", "# plain text\n", false);
    awaitTop("an empty tree");
    assertEquals(UiText.OUTLINE_NOT_MARKDOWN.text(), window.outlineDock().summary().getText());

    controller.execute(CommandId.PREVIOUS_TAB);
    awaitTop("the guide's tree again", "Mock guide");
  }

  @Test
  void aClickMovesTheCaretAndThePreview() throws Exception {
    StringBuilder text = new StringBuilder("# Doc\n\n");
    for (int i = 1; i <= 15; i++) {
      text.append("## Section ").append(i).append("\n\n");
      for (int j = 0; j < 18; j++) {
        text.append("Line ").append(j).append(" of section ").append(i).append(".\n\n");
      }
    }
    openFile("long.md", text.toString(), true);
    controller.documents().setMarkdownMode(MarkdownViewMode.SPLIT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            MarkdownPane pane = window.activePreview();
            return pane != null && pane.text().getText().contains("Section 15");
          }
        },
        "preview rendered");
    showOutline();
    awaitTop("the tree", "Doc");
    TreeItem section = window.outlineDock().tree().getItem(0).getItem(9);
    assertEquals("Section 10", section.getText());
    click(section);
    // "# Doc" and a blank line, then 38 lines per section.
    assertEquals(2 + 9 * 38, caretLine());
    final StyledText preview = window.activePreview().text();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return preview.getLine(preview.getTopIndex()).startsWith("Section 10");
          }
        },
        "the preview at Section 10, at " + preview.getLine(preview.getTopIndex()));
    assertEquals(section, window.outlineDock().tree().getSelection()[0]);

    TreeItem first = window.outlineDock().tree().getItem(0).getItem(1);
    Event enter = new Event();
    enter.item = first;
    window.outlineDock().tree().notifyListeners(SWT.DefaultSelection, enter);
    flush();
    assertEquals(2 + 38, caretLine());
  }

  @Test
  void theSelectionFollowsTheCaret() throws Exception {
    openFile("guide.md", GUIDE, true);
    showOutline();
    awaitTop("the tree", "Mock guide");
    EditorController editor = controller.documents().activeEditor();
    editor.goTo(6, 0);
    flush();
    Tree tree = window.outlineDock().tree();
    assertEquals("Steps", tree.getSelection()[0].getText());
    editor.goTo(0, 0);
    flush();
    assertEquals("Mock guide", tree.getSelection()[0].getText());
  }

  @Test
  void theToggleAndHideShowAndHideTheDock() throws Exception {
    openFile("guide.md", GUIDE, true);
    CommandStates states = new CommandStates(controller);
    assertFalse(window.outlineShown());
    showOutline();
    assertTrue(states.checked(CommandId.MARKDOWN_OUTLINE));
    assertTrue(window.outlineDock().control().getBounds().width > 0);
    Button hide =
        (Button) ((Composite) window.outlineDock().control().getChildren()[0]).getChildren()[0];
    assertEquals(UiText.OUTLINE_HIDE.text(), hide.getText());
    hide.notifyListeners(SWT.Selection, new Event());
    flush();
    assertFalse(window.outlineShown());
    assertFalse(states.checked(CommandId.MARKDOWN_OUTLINE));
    showOutline();
    controller.execute(CommandId.MARKDOWN_OUTLINE);
    flush();
    assertFalse(window.outlineShown());
  }

  @Test
  void aThemeSwitchReachesTheTree() throws Exception {
    openFile("guide.md", GUIDE, true);
    showOutline();
    final OutlineDock dock = window.outlineDock();
    assertFalse(dock.dark());
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.dark();
          }
        },
        "dark");
    if (PlatformTraits.PLATFORM.colorsTreesUnderDark()) {
      Palette dark = Palette.DARK;
      assertEquals(
          new RGB(dark.background().red(), dark.background().green(), dark.background().blue()),
          dock.tree().getBackground().getRGB());
    }
    controller.execute(CommandId.THEME_LIGHT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !dock.dark();
          }
        },
        "light");
  }

  @Test
  void aLongHeadingListFillsInSlices() throws Exception {
    int count = OutlineDock.SLICE_ITEMS * 4 + 7;
    StringBuilder text = new StringBuilder("# Top\n");
    for (int i = 1; i < count; i++) {
      text.append("## H").append(i).append('\n');
    }
    openFile("many.md", text.toString(), true);
    showOutline();
    awaitTop("the tree", "Top");
    OutlineDock dock = window.outlineDock();
    assertEquals(count - 1, dock.tree().getItem(0).getItemCount());
    assertTrue(dock.slices() >= 5, "slices " + dock.slices());
    assertEquals("H" + (count - 1), dock.tree().getItem(0).getItem(count - 2).getText());
  }

  @Test
  void anEditKeepsTheItemsBeforeItAndTheirExpansion() throws Exception {
    openFile("guide.md", GUIDE, true);
    showOutline();
    awaitTop("the tree", "Mock guide");
    final Tree tree = window.outlineDock().tree();
    TreeItem setup = tree.getItem(0).getItem(0);
    setup.setExpanded(false);
    EditorController editor = controller.documents().activeEditor();
    // Before "## Usage" on line 8: a sibling, then a child of the new sibling.
    editor.goTo(8, 0);
    editor.type("## Extra\n\n### Inner\n\n");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.outlineDock().filled()
                && tree.getItem(0).getItemCount() == 3
                && tree.getItem(0).getItem(1).getItemCount() == 1;
          }
        },
        "the new headings");
    TreeItem top = tree.getItem(0);
    assertTrue(setup == top.getItem(0) && !setup.getExpanded());
    assertEquals("Extra", top.getItem(1).getText());
    assertTrue(top.getItem(1).getExpanded());
    assertEquals("Inner", top.getItem(1).getItem(0).getText());
    assertEquals("Usage", top.getItem(2).getText());
    editor.goTo(0, 0);
    editor.type("# First\n\n");
    awaitTop("a new first heading", "First", "Mock guide");
    assertEquals(3, tree.getItem(1).getItemCount());
  }

  /** The center sash holds the editors, then the dock. */
  @Test
  void theDockStandsRightOfTheEditors() throws Exception {
    openFile("guide.md", GUIDE, true);
    showOutline();
    Control editors = window.tabs().getParent();
    Control dock = window.outlineDock().control();
    assertTrue(
        dock.getBounds().x >= editors.getBounds().x + editors.getBounds().width,
        dock.getBounds() + " " + editors.getBounds());
  }
}
