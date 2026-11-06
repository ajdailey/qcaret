package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
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
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
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
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main toolbar, the editor menu, the tab menu's close and folder items and the line-number
 * toggle in a real window. Popups are never shown to a pump: each test hides one before draining,
 * since a win32 popup would hold the test in its loop.
 */
@Tag("swt")
class ToolbarAndMenusTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private ConfigStore config;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
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
    config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf")));
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            config,
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
    drain();
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

  private void openFile(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
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
        "open");
    drain();
  }

  private MenuItem item(CommandId command) {
    return window.menuBar().item(command);
  }

  private ToolItem button(CommandId command) {
    return window.mainToolBar().item(command);
  }

  private void click(CommandId command) {
    button(command).notifyListeners(SWT.Selection, new Event());
    drain();
  }

  /** Sends the pointer's context menu request at an offset and returns the hidden popup. */
  private Menu rightClick(StyledText text, int offset) {
    Point at = text.getLocationAtOffset(offset);
    Point point = text.toDisplay(at.x + 1, at.y + 1);
    Event event = new Event();
    event.x = point.x;
    event.y = point.y;
    event.detail = SWT.MENU_MOUSE;
    return menuFor(text, event);
  }

  private Menu menuFor(StyledText text, Event event) {
    text.notifyListeners(SWT.MenuDetect, event);
    Menu menu = window.editorMenu().menu();
    assertNotNull(menu);
    menu.setVisible(false);
    return menu;
  }

  private static MenuItem entry(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
    }
    throw new AssertionError(command.name());
  }

  private SelectionSet selections() {
    return controller.documents().activeEditor().selections().selections();
  }

  private void select(int anchor, int caret) {
    controller.documents().activeEditor().selectFromView(anchor, caret);
    drain();
  }

  @Test
  void theToolbarHoldsTheCommandsInOrderWithIconsAndKeysInTheToolTips() {
    ToolBar bar = window.mainToolBar().bar();
    assertTrue(bar.getVisible());
    assertEquals(MainToolBar.COMMANDS.length, bar.getItemCount());
    Keymap keymap = controller.keymap();
    for (int i = 0; i < MainToolBar.COMMANDS.length; i++) {
      CommandId command = MainToolBar.COMMANDS[i];
      ToolItem item = bar.getItem(i);
      if (command == null) {
        assertTrue((item.getStyle() & SWT.SEPARATOR) != 0);
        continue;
      }
      assertSame(item, button(command));
      assertNotNull(item.getImage(), command.name());
      String tip = item.getToolTipText();
      assertTrue(tip.startsWith(command.title()), tip);
      assertTrue(tip.contains(keymap.keysText(command)), tip);
    }
    assertTrue(bar.getBounds().y < window.tabs().getParent().getParent().getBounds().y + 1);
  }

  @Test
  void aKeyChangeReachesTheToolTip() {
    KeyStroke stroke = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k');
    Keymap changed =
        controller
            .keymap()
            .withOverrides(ShortcutOverrides.NONE.with(CommandId.SAVE, List.of(stroke)));
    window.setKeymap(changed);
    String keys = changed.keysText(CommandId.SAVE);
    assertEquals(
        CommandId.SAVE.title() + " (" + keys + ")", button(CommandId.SAVE).getToolTipText());
  }

  @Test
  void theButtonsRunTheirCommandsAndFollowTheCommandStates() throws Exception {
    assertTrue(button(CommandId.NEW).getEnabled());
    assertFalse(button(CommandId.SAVE).getEnabled());
    assertFalse(button(CommandId.CUT).getEnabled());
    click(CommandId.NEW);
    assertEquals(1, window.tabs().getItemCount());
    assertTrue(button(CommandId.SAVE).getEnabled());
    assertFalse(button(CommandId.CUT).getEnabled());
    assertFalse(button(CommandId.COPY).getEnabled());
    assertFalse(button(CommandId.UNDO).getEnabled());
    assertFalse(button(CommandId.SAVE_ALL).getEnabled());
    controller.documents().activeEditor().type("typed");
    drain();
    assertTrue(button(CommandId.SAVE_ALL).getEnabled());
    assertTrue(button(CommandId.UNDO).getEnabled());
    assertFalse(button(CommandId.REDO).getEnabled());
    click(CommandId.UNDO);
    assertEquals("", window.activeText().getText());
    drain();
    assertFalse(button(CommandId.UNDO).getEnabled());
    click(CommandId.REDO);
    assertEquals("typed", window.activeText().getText());
    controller.documents().activeEditor().selectFromView(0, 5);
    drain();
    assertTrue(button(CommandId.CUT).getEnabled());
    assertTrue(item(CommandId.COPY).getEnabled());
    click(CommandId.FIND);
    assertTrue(window.findBar().control().getVisible());
    int before = window.editorFont().getFontData()[0].getHeight();
    click(CommandId.ZOOM_IN);
    assertEquals(before + 1, window.editorFont().getFontData()[0].getHeight());
    click(CommandId.ZOOM_OUT);
    assertEquals(before, window.editorFont().getFontData()[0].getHeight());
    controller.execute(CommandId.READ_ONLY);
    drain();
    assertFalse(button(CommandId.PASTE).getEnabled());
    assertTrue(button(CommandId.COPY).getEnabled());
  }

  @Test
  void viewToolbarHidesTheRowAndSavesTheChoice() throws Exception {
    final ToolBar bar = window.mainToolBar().bar();
    int shownTop = window.tabs().toDisplay(0, 0).y;
    MenuItem toggle = window.menuBar().item(CommandId.TOOLBAR);
    window.menuBar().sync(CommandGroup.VIEW);
    assertTrue(toggle.getSelection());
    toggle.notifyListeners(SWT.Selection, new Event());
    drain();
    assertFalse(bar.getVisible());
    assertFalse(toggle.getSelection());
    assertTrue(window.tabs().toDisplay(0, 0).y < shownTop);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !config.load().state().toolbar();
          }
        },
        "saved");
    controller.execute(CommandId.TOOLBAR);
    drain();
    assertTrue(bar.getVisible());
    assertEquals(shownTop, window.tabs().toDisplay(0, 0).y);
  }

  @Test
  void theButtonsTakeTheOtherInkOnADarkBackground() {
    // Hidden, the ink comes from getBackground on every platform: GTK 4 draws no set color.
    window.setToolbarShown(false);
    ToolBar bar = window.mainToolBar().bar();
    bar.setBackground(new Color(240, 240, 240));
    window.toolIcons().reink();
    Image light = button(CommandId.SAVE).getImage();
    bar.setBackground(new Color(30, 30, 30));
    window.toolIcons().reink();
    assertTrue(window.toolIcons().darkBackground());
    assertNotSame(light, button(CommandId.SAVE).getImage());
    assertTrue(light.isDisposed());
  }

  /** Items for Undo, Cut and Copy stay disabled here: their keys are not accelerators. */
  @Test
  void editKeysWorkInEveryFocusWhateverTheItemsShow() throws Exception {
    assumeTrue("gtk".equals(SWT.getPlatform()) && !"1".equals(System.getenv("SWT_GTK4")));
    openFile("keys.txt", "alpha\n");
    final StyledText text = window.activeText();
    window.shell().forceActive();
    controller.execute(CommandId.FIND);
    drain();
    final Combo field = window.findBar().field();
    field.setText("needle");
    field.setSelection(new Point(0, 6));
    field.setFocus();
    drain();
    assertFalse(item(CommandId.CUT).getEnabled());
    assertFalse(item(CommandId.COPY).getEnabled());
    assertFalse(item(CommandId.UNDO).getEnabled());
    press('x');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return field.getText().isEmpty();
          }
        },
        "Ctrl+X in the find field");
    assertEquals("needle", clipboard());
    press('v');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "needle".equals(field.getText());
          }
        },
        "Ctrl+V in the find field");
    field.setSelection(new Point(0, 3));
    press('c');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "nee".equals(clipboard());
          }
        },
        "Ctrl+C in the find field");
    controller.documents().activeEditor().type("typed");
    drain();
    assertEquals(true, item(CommandId.UNDO).getEnabled());
    press('z');
    drain();
    assertEquals("typedalpha\n", text.getText());

    controller.documents().activeEditor().selectFromView(0, 5);
    controller.execute(CommandId.READ_ONLY);
    drain();
    text.setFocus();
    drain();
    assertTrue(item(CommandId.COPY).getEnabled());
    press('c');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "typed".equals(clipboard());
          }
        },
        "Ctrl+C in a read-only view");
  }

  private void press(char key) {
    post(SWT.KeyDown, SWT.CTRL, 0);
    post(SWT.KeyDown, 0, key);
    post(SWT.KeyUp, 0, key);
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

  private String clipboard() {
    Clipboard clipboard = new Clipboard(display);
    try {
      return (String) clipboard.getContents(TextTransfer.getInstance());
    } finally {
      clipboard.dispose();
    }
  }

  @Test
  void aThemeSwitchReinksFromTheDrawnBackgroundAndFadesDisabledButtons() {
    assumeTrue("gtk".equals(SWT.getPlatform()));
    final ToolIcons icons = window.toolIcons();
    assertFalse(icons.darkBackground());
    assertSame(icons.get(ToolIcons.SAVE), button(CommandId.SAVE).getImage());
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return icons.darkBackground();
          }
        },
        "dark ink");
    assertFalse(button(CommandId.SAVE).getEnabled());
    assertNotSame(icons.get(ToolIcons.SAVE), button(CommandId.SAVE).getImage());
    assertSame(icons.get(ToolIcons.NEW), button(CommandId.NEW).getImage());
    click(CommandId.NEW);
    assertSame(icons.get(ToolIcons.SAVE), button(CommandId.SAVE).getImage());
    controller.execute(CommandId.THEME_LIGHT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !icons.darkBackground();
          }
        },
        "light ink");
  }

  @Test
  void menuItemsShowTheirCommandsIconsAndCheckItemsNone() {
    assumeFalse("cocoa".equals(SWT.getPlatform()) || gtk4(), "no menu images");
    ToolIcons icons = window.menuIcons().icons();
    assertSame(icons.get(ToolIcons.NEW), item(CommandId.NEW).getImage());
    assertSame(icons.get(ToolIcons.HELP), item(CommandId.USER_GUIDE).getImage());
    assertNotSame(icons.get(ToolIcons.NEW), item(CommandId.TOOLBAR).getImage());
    for (CommandId command : CommandId.values()) {
      MenuItem menuItem = window.menuBar().item(command);
      if (menuItem != null && command.check() && menuItem.getImage() != null) {
        assertSame(icons.blank(), menuItem.getImage(), command.name());
      }
    }
  }

  @Test
  void theEditorMenuShowsTheMenuBarsIcons() throws Exception {
    assumeFalse("cocoa".equals(SWT.getPlatform()) || gtk4(), "no menu images");
    openFile("icons.txt", "one\n");
    Menu menu = rightClick(window.activeText(), 1);
    ToolIcons icons = window.menuIcons().icons();
    assertSame(icons.get(ToolIcons.SELECT_ALL), entry(menu, CommandId.SELECT_ALL).getImage());
    assertSame(item(CommandId.SELECT_ALL).getImage(), entry(menu, CommandId.SELECT_ALL).getImage());
  }

  @Test
  void listingCascadesCarryNoIcons() {
    Menu file = window.menuBar().menu(CommandGroup.FILE);
    for (MenuItem holder : file.getItems()) {
      if (holder.getMenu() != null) {
        for (MenuItem entry : holder.getMenu().getItems()) {
          assertNull(entry.getImage(), entry.getText());
        }
      }
    }
  }

  @Test
  void aKeyChangeRebuildsTheMenuBarWithItsIcons() {
    assumeFalse("cocoa".equals(SWT.getPlatform()) || gtk4(), "no menu images");
    window.setKeymap(controller.keymap());
    ToolIcons icons = window.menuIcons().icons();
    assertSame(icons.get(ToolIcons.OPEN), item(CommandId.OPEN).getImage());
  }

  @Test
  void aThemeSwitchReinksMenuItemsBothWaysAndFadesDisabledOnes() {
    assumeTrue("gtk".equals(SWT.getPlatform()) && !gtk4());
    final ToolIcons icons = window.menuIcons().icons();
    assertFalse(icons.darkBackground());
    Image light = item(CommandId.NEW).getImage();
    assertSame(icons.get(ToolIcons.SAVE), item(CommandId.SAVE).getImage());
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return icons.darkBackground();
          }
        },
        "light ink");
    assertTrue(light.isDisposed());
    assertFalse(item(CommandId.SAVE).getEnabled());
    assertNotSame(icons.get(ToolIcons.SAVE), item(CommandId.SAVE).getImage());
    assertSame(icons.get(ToolIcons.NEW), item(CommandId.NEW).getImage());
    click(CommandId.NEW);
    window.menuBar().syncEnablement();
    assertSame(icons.get(ToolIcons.SAVE), item(CommandId.SAVE).getImage());
    controller.execute(CommandId.THEME_LIGHT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !icons.darkBackground();
          }
        },
        "dark ink");
    assertSame(icons.get(ToolIcons.NEW), item(CommandId.NEW).getImage());
  }

  private static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }

  @Test
  void aNarrowWindowKeepsItsWidthAndTheEditor() throws Exception {
    openFile("narrow.txt", "text\n");
    window.shell().setSize(240, 400);
    drain();
    int client = window.shell().getClientArea().width;
    assertTrue(window.mainToolBar().bar().getBounds().width <= client);
    assertTrue(window.tabs().getBounds().width <= client);
    assertTrue(window.tabs().getBounds().height > 100);
  }

  @Test
  void aRightClickInTheSelectionKeepsItAndOneOutsideMovesTheCaret() throws Exception {
    openFile("menu.txt", "one two three\nfour\n");
    StyledText text = window.activeText();
    select(4, 7);
    rightClick(text, 5);
    assertEquals(SelectionSet.of(List.of(Selection.of(4, 7)), 0), selections());
    rightClick(text, 10);
    assertEquals(SelectionSet.caret(10), selections());
  }

  @Test
  void aRightClickInASecondaryCaretsSelectionKeepsThemAll() throws Exception {
    openFile("multi.txt", "one two three\n");
    controller.documents().activeEditor().selectionsFromView(new int[] {0, 3, 4, 7}, 0);
    drain();
    rightClick(window.activeText(), 5);
    assertEquals(2, selections().size());
  }

  @Test
  void aKeyboardMenuMovesNothing() throws Exception {
    openFile("keys.txt", "one two three\n");
    select(4, 7);
    Event event = new Event();
    event.detail = SWT.MENU_KEYBOARD;
    menuFor(window.activeText(), event);
    assertEquals(SelectionSet.of(List.of(Selection.of(4, 7)), 0), selections());
  }

  @Test
  void theEditorMenuRunsItsCommandsAndShowsTheirKeys() throws Exception {
    openFile("run.txt", "one two\n");
    StyledText text = window.activeText();
    select(0, 3);
    Menu menu = rightClick(text, 1);
    assertEquals(EditorMenu.COMMANDS.length, menu.getItemCount());
    MenuItem copy = entry(menu, CommandId.COPY);
    assertTrue(copy.getText().endsWith("\t" + controller.keymap().menuKeyText(CommandId.COPY)));
    copy.notifyListeners(SWT.Selection, new Event());
    assertEquals("one", window.clipboardText());
    entry(menu, CommandId.TOGGLE_BOOKMARK).notifyListeners(SWT.Selection, new Event());
    drain();
    assertTrue(window.menuBar().states().enabled(CommandId.NEXT_BOOKMARK));
    entry(menu, CommandId.SELECT_ALL).notifyListeners(SWT.Selection, new Event());
    drain();
    assertEquals("one two\n", text.getSelectionText());
    assertSame(menu, rightClick(text, 1));
  }

  @Test
  void theEditorMenuFollowsTheCommandStates() throws Exception {
    openFile("state.txt", "one\n");
    Menu plain = rightClick(window.activeText(), 1);
    assertFalse(entry(plain, CommandId.TOGGLE_FOLD).getEnabled());
    assertFalse(entry(plain, CommandId.COPY).getEnabled());
    controller.documents().activeEditor().selectFromView(0, 3);
    controller.execute(CommandId.READ_ONLY);
    drain();
    Menu menu = rightClick(window.activeText(), 1);
    assertFalse(entry(menu, CommandId.CUT).getEnabled());
    assertFalse(entry(menu, CommandId.PASTE).getEnabled());
    assertTrue(entry(menu, CommandId.COPY).getEnabled());
  }

  @Test
  void theEditorMenuActsOnTheViewItOpenedOver() throws Exception {
    openFile("split.txt", "one two\n");
    controller.execute(CommandId.SPLIT_HORIZONTAL);
    drain();
    List<EditorPane> panes = window.activeEditors();
    assertEquals(2, panes.size());
    StyledText first = panes.get(0).text();
    StyledText second = panes.get(1).text();
    first.setFocus();
    drain();
    Menu menu = rightClick(second, 2);
    assertSame(panes.get(1), window.activeEditor());
    entry(menu, CommandId.SELECT_ALL).notifyListeners(SWT.Selection, new Event());
    drain();
    assertEquals("one two\n", second.getSelectionText());
    assertEquals("", first.getSelectionText());
  }

  @Test
  void aKeyChangeReachesTheEditorMenu() throws Exception {
    openFile("key.txt", "one\n");
    Menu before = rightClick(window.activeText(), 1);
    KeyStroke stroke = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k');
    Keymap changed =
        controller
            .keymap()
            .withOverrides(ShortcutOverrides.NONE.with(CommandId.COPY, List.of(stroke)));
    window.setKeymap(changed);
    assertTrue(before.isDisposed());
    MenuItem copy = entry(rightClick(window.activeText(), 1), CommandId.COPY);
    assertTrue(copy.getText().endsWith("\t" + changed.keysText(CommandId.COPY)));
  }

  private MenuItem tabEntry(Menu menu, UiText label) {
    for (MenuItem item : menu.getItems()) {
      if (item.getText().equals(label.text())) {
        return item;
      }
    }
    throw new AssertionError(label.name());
  }

  @Test
  void theTabMenuClosesEachSide() throws Exception {
    openFile("a.txt", "a");
    openFile("b.txt", "b");
    openFile("c.txt", "c");
    openFile("d.txt", "d");
    Menu first = window.showTabMenu(0);
    first.setVisible(false);
    assertFalse(tabEntry(first, UiText.TAB_CLOSE_LEFT).getEnabled());
    assertTrue(tabEntry(first, UiText.TAB_CLOSE_RIGHT).getEnabled());
    assertTrue(tabEntry(first, UiText.TAB_OPEN_FOLDER).getEnabled());
    Menu second = window.showTabMenu(1);
    second.setVisible(false);
    tabEntry(second, UiText.TAB_CLOSE_LEFT).notifyListeners(SWT.Selection, new Event());
    drain();
    assertEquals(3, window.tabs().getItemCount());
    assertEquals("b.txt", window.tabs().getItem(0).getText());
    assertEquals("d.txt", window.tabs().getSelection().getText());
    Menu third = window.showTabMenu(0);
    third.setVisible(false);
    tabEntry(third, UiText.TAB_CLOSE_RIGHT).notifyListeners(SWT.Selection, new Event());
    drain();
    assertEquals(1, window.tabs().getItemCount());
    assertEquals("b.txt", window.tabs().getSelection().getText());
    Menu last = window.showTabMenu(0);
    last.setVisible(false);
    assertFalse(tabEntry(last, UiText.TAB_CLOSE_RIGHT).getEnabled());
  }

  @Test
  void anUntitledTabHasNoFolder() {
    controller.execute(CommandId.NEW);
    drain();
    Menu menu = window.showTabMenu(0);
    menu.setVisible(false);
    assertFalse(tabEntry(menu, UiText.TAB_OPEN_FOLDER).getEnabled());
  }

  @Test
  void viewLineNumbersNarrowsTheGutterOfEveryViewAndLaterOnes() throws Exception {
    openFile("numbers.txt", "one\ntwo\n");
    EditorPane pane = window.activeEditor();
    int shown = pane.gutterWidth();
    int margin = pane.text().getLeftMargin();
    MenuItem toggle = window.menuBar().item(CommandId.LINE_NUMBERS);
    window.menuBar().sync(CommandGroup.VIEW);
    assertTrue(toggle.getSelection());
    toggle.notifyListeners(SWT.Selection, new Event());
    drain();
    assertFalse(toggle.getSelection());
    int hidden = pane.gutterWidth();
    assertTrue(hidden < shown);
    assertEquals(margin - (shown - hidden), pane.text().getLeftMargin());
    assertTrue(pane.foldColumn() >= EditorPane.BOOKMARK_COLUMN);
    controller.execute(CommandId.SPLIT_VERTICAL);
    drain();
    assertEquals(hidden, window.activeEditors().get(1).gutterWidth());
    openFile("later.txt", "one\n");
    assertEquals(hidden, window.activeEditor().gutterWidth());
    controller.execute(CommandId.LINE_NUMBERS);
    drain();
    assertEquals(shown, window.activeEditor().gutterWidth());
    assertEquals(shown, pane.gutterWidth());
  }
}
