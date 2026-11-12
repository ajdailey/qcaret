package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The command-to-icon table, the SVG set, and {@link MenuIcons} per platform on real items. */
@Tag("swt")
class MenuIconsTest {
  /** The root element every icon shares. */
  private static final String ROOT =
      "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"0 0 16 16\""
          + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\""
          + " stroke-linejoin=\"round\">";

  private static final Pattern ROOT_ELEMENT = Pattern.compile("<svg[^>]*>");

  /** Attributes only the root sets. */
  private static final Pattern OVERRIDE =
      Pattern.compile("stroke-width=|stroke-linecap=|stroke-linejoin=|viewBox=|transform=");

  private static final Path SOURCES = Path.of("src", "icons");

  private Display display;
  private Shell shell;
  private ToolBar bar;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    bar = new ToolBar(shell, SWT.FLAT);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }

  private static Map<CommandId, String> table() {
    Map<CommandId, String> table = new EnumMap<CommandId, String>(CommandId.class);
    table.put(CommandId.NEW, "new");
    table.put(CommandId.OPEN, "open");
    table.put(CommandId.SAVE, "save");
    table.put(CommandId.SAVE_AS, "save-as");
    table.put(CommandId.SAVE_ALL, "save-all");
    table.put(CommandId.PRINT, "print");
    table.put(CommandId.RELOAD, "reload");
    table.put(CommandId.CLOSE, "close");
    table.put(CommandId.QUIT, "quit");
    table.put(CommandId.UNDO, "undo");
    table.put(CommandId.REDO, "redo");
    table.put(CommandId.CUT, "cut");
    table.put(CommandId.COPY, "copy");
    table.put(CommandId.PASTE, "paste");
    table.put(CommandId.SELECT_ALL, "select-all");
    table.put(CommandId.PREFERENCES, "preferences");
    table.put(CommandId.GO_TO_LINE, "go-to-line");
    table.put(CommandId.ZOOM_IN, "zoom-in");
    table.put(CommandId.ZOOM_OUT, "zoom-out");
    table.put(CommandId.FIND, "search");
    table.put(CommandId.FIND_NEXT, "find-next");
    table.put(CommandId.FIND_PREVIOUS, "find-previous");
    table.put(CommandId.REPLACE, "replace");
    table.put(CommandId.HYPERSEARCH, "hypersearch");
    table.put(CommandId.USER_GUIDE, "help");
    table.put(CommandId.ABOUT, "about");
    return table;
  }

  private static List<Path> sources() throws IOException {
    List<Path> files = new ArrayList<Path>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(SOURCES, "*.svg")) {
      for (Path file : stream) {
        files.add(file);
      }
    }
    assertFalse(files.isEmpty());
    return files;
  }

  private static String stem(Path file) {
    String name = file.getFileName().toString();
    return name.substring(0, name.length() - ".svg".length());
  }

  @Test
  void eachCommandHasItsIconAndNoOtherCommandHasOne() {
    Map<CommandId, String> table = table();
    for (CommandId command : CommandId.values()) {
      assertEquals(table.get(command), MenuIcons.icon(command), command.name());
    }
  }

  @Test
  void noIconSitsOnACheckCommand() {
    for (CommandId command : table().keySet()) {
      assertFalse(command.check(), command.name());
    }
  }

  @Test
  void everyToolbarCommandKeepsItsIconInTheTable() {
    for (CommandId command : MainToolBar.COMMANDS) {
      assertTrue(command == null || MenuIcons.icon(command) != null);
    }
  }

  @Test
  void everyIconNameHasASource() throws Exception {
    for (Field field : ToolIcons.class.getDeclaredFields()) {
      if (field.getType() == String.class
          && Modifier.isStatic(field.getModifiers())
          && !Modifier.isPrivate(field.getModifiers())) {
        Path source = SOURCES.resolve(field.get(null) + ".svg");
        assertTrue(Files.exists(source), source.toString());
      }
    }
  }

  @Test
  void everyIconHasBothInksAtBothSizes() throws IOException {
    for (Path file : sources()) {
      for (String ink : List.of("light", "dark")) {
        for (int size : new int[] {16, 32}) {
          String resource = "icons/tool/" + stem(file) + "-" + ink + "-" + size + ".png";
          try (InputStream in = ToolIcons.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
          }
        }
      }
    }
  }

  @Test
  void everyIconTakesItsStyleFromTheSharedRootElement() throws IOException {
    for (Path file : sources()) {
      String svg = Files.readString(file, StandardCharsets.UTF_8);
      Matcher root = ROOT_ELEMENT.matcher(svg);
      assertTrue(root.find(), file.toString());
      assertEquals(ROOT, root.group(), file.toString());
      assertFalse(OVERRIDE.matcher(svg.substring(root.end())).find(), file.toString());
    }
  }

  private Menu menu() {
    Menu menu = new Menu(shell, SWT.DROP_DOWN);
    new MenuItem(menu, SWT.PUSH).setData(CommandId.SAVE);
    new MenuItem(menu, SWT.PUSH).setData(CommandId.TOGGLE_BOOKMARK);
    new MenuItem(menu, SWT.SEPARATOR);
    new MenuItem(menu, SWT.CHECK).setData(CommandId.SAVE_AS);
    new MenuItem(menu, SWT.RADIO).setData(CommandId.SAVE_ALL);
    MenuItem holder = new MenuItem(menu, SWT.CASCADE);
    Menu cascade = new Menu(shell, SWT.DROP_DOWN);
    holder.setMenu(cascade);
    new MenuItem(cascade, SWT.PUSH).setData(CommandId.TOGGLE_BOOKMARK);
    return menu;
  }

  @Test
  void cocoaItemsShowNoImage() {
    Menu menu = menu();
    new MenuIcons(bar, PlatformTraits.of("cocoa", false)).decorate(menu);
    for (MenuItem item : menu.getItems()) {
      assertNull(item.getImage());
    }
  }

  @Test
  void gtkMenusAlignTheirItemsWithTheBlankImage() {
    assumeFalse(gtk4(), "GTK 4 SWT ignores MenuItem.setImage");
    Menu menu = menu();
    MenuIcons icons = new MenuIcons(bar, PlatformTraits.of("gtk", false));
    icons.decorate(menu);
    Image blank = icons.icons().blank();
    MenuItem[] items = menu.getItems();
    assertSame(icons.icons().get(ToolIcons.SAVE), items[0].getImage());
    assertSame(blank, items[1].getImage());
    assertNull(items[2].getImage());
    assertSame(blank, items[3].getImage());
    assertSame(blank, items[4].getImage());
    assertSame(blank, items[5].getImage());
    assertNull(items[5].getMenu().getItem(0).getImage(), "a cascade without an icon stays bare");
  }

  @Test
  void win32LeavesCheckAndPlainItemsBare() {
    assumeFalse(gtk4(), "GTK 4 SWT ignores MenuItem.setImage");
    Menu menu = menu();
    MenuIcons icons = new MenuIcons(bar, PlatformTraits.of("win32", false));
    icons.decorate(menu);
    MenuItem[] items = menu.getItems();
    assertSame(icons.icons().get(ToolIcons.SAVE), items[0].getImage());
    assertNull(items[1].getImage());
    assertNull(items[3].getImage());
    assertNull(items[4].getImage());
    assertNull(items[5].getImage());
  }

  @Test
  void win32InkKeepsTheStartupSetting() {
    bar.setBackground(new Color(240, 240, 240));
    MenuIcons icons = new MenuIcons(bar, PlatformTraits.of("win32", false));
    assertFalse(icons.icons().darkBackground());
    bar.setBackground(new Color(30, 30, 30));
    icons.reink();
    assertFalse(icons.icons().darkBackground(), "a win32 toolbar says nothing about menus");
  }
}
