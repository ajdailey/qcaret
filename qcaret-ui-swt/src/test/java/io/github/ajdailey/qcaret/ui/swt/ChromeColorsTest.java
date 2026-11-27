package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qcaret.ui.swt.platform.Contrast;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogColors;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link ChromeColors}: the win32 implementation constructed on any platform, read back through
 * public API, and the native implementation of GTK and cocoa.
 */
@Tag("swt")
class ChromeColorsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final PlatformTraits WIN32 = PlatformTraits.of("win32", false);
  // The neutral grays of the dark win32 chrome.
  private static final RGB BACKGROUND = new RGB(0x20, 0x20, 0x20);
  private static final RGB TEXT = new RGB(0xff, 0xff, 0xff);
  private static final RGB FIELD = new RGB(0x2b, 0x2b, 0x2b);
  private static final RGB FACE = new RGB(0x33, 0x33, 0x33);
  private static final RGB HINT = new RGB(0x9a, 0x9a, 0x9a);
  private static final RGB OWN_BACKGROUND = new RGB(0x10, 0x20, 0x30);
  private static final RGB OWN_TEXT = new RGB(0x40, 0x50, 0x60);

  /** Checked tool item backgrounds measured on Windows 11: dark start, light start. */
  private static final RGB CHECKED_DARK_START = new RGB(0x45, 0x51, 0x60);

  private static final RGB CHECKED_LIGHT_START = new RGB(0xcc, 0xe8, 0xff);

  private Display display;
  private Shell shell;
  private Composite surface;
  private Label label;
  private Link link;
  private ToolBar bar;
  private Button check;
  private Canvas gauge;
  private Text field;
  private Tree tree;
  private Table table;
  private List<Control> kept;
  private ToolIcons icons;
  private List<ToolItem> items;
  private ToolItem source;
  private ToolItem preview;

  @BeforeEach
  void create() {
    display = new Display();
    shell = new Shell(display);
    surface = new Composite(shell, SWT.NONE);
    label = new Label(surface, SWT.NONE);
    link = new Link(surface, SWT.NONE);
    link.setText("<a>Warnings</a>");
    bar = new ToolBar(surface, SWT.FLAT);
    check = new Button(surface, SWT.CHECK);
    gauge = new Canvas(surface, SWT.NONE);
    field = new Text(surface, SWT.SINGLE);
    field.setMessage("filter");
    tree = new Tree(surface, SWT.NONE);
    tree.setHeaderVisible(true);
    table = new Table(surface, SWT.NONE);
    table.setHeaderVisible(true);
    CTabFolder tabs = new CTabFolder(surface, SWT.NONE);
    Composite marked = new Composite(surface, SWT.NONE);
    marked.setData(EditorPane.PALETTE_COLORED, Boolean.TRUE);
    kept =
        List.of(
            new Button(surface, SWT.PUSH),
            new Button(surface, SWT.ARROW | SWT.DOWN),
            new Combo(surface, SWT.DROP_DOWN),
            new StyledText(surface, SWT.NONE),
            tabs,
            new Label(tabs, SWT.NONE),
            marked,
            new Text(marked, SWT.SINGLE),
            new Label(marked, SWT.NONE));
    icons = new ToolIcons(bar);
    items = new ArrayList<ToolItem>();
    for (String name : names()) {
      ToolItem item = new ToolItem(bar, SWT.PUSH);
      assertTrue(icons.apply(item, name), name);
      items.add(item);
    }
    source = radio("Source");
    preview = radio("Preview");
    source.setSelection(true);
    icons.applyChecked(source);
    icons.applyChecked(preview);
    items.add(icons.item(surface, ToolIcons.TAB_LIST_DOWN, "More"));
    // A kept control carries its own colors, as the editor and tab strip do.
    for (Control control : kept) {
      control.setBackground(new Color(OWN_BACKGROUND));
      control.setForeground(new Color(OWN_TEXT));
    }
  }

  @AfterEach
  void dispose() {
    display.dispose();
  }

  private ToolItem radio(String text) {
    ToolItem item = new ToolItem(bar, SWT.RADIO);
    item.setText(text);
    return item;
  }

  /** Every icon name {@link ToolIcons} declares. */
  static List<String> names() {
    List<String> names = new ArrayList<String>();
    for (Field constant : ToolIcons.class.getDeclaredFields()) {
      int modifiers = constant.getModifiers();
      if (constant.getType() == String.class
          && Modifier.isStatic(modifiers)
          && !Modifier.isPrivate(modifiers)) {
        try {
          names.add((String) constant.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    }
    return names;
  }

  private List<Control> painted() {
    return List.of(
        surface, label, link, bar, check, gauge, items.get(items.size() - 1).getParent());
  }

  private static List<RGB> colors(List<Control> controls) {
    List<RGB> colors = new ArrayList<RGB>();
    for (Control control : controls) {
      colors.add(control.getBackground().getRGB());
      colors.add(control.getForeground().getRGB());
    }
    return colors;
  }

  /** Returns the tree's colors, then the table's: background, text, header, header text. */
  private List<RGB> treeColors() {
    return List.of(
        tree.getBackground().getRGB(),
        tree.getForeground().getRGB(),
        tree.getHeaderBackground().getRGB(),
        tree.getHeaderForeground().getRGB(),
        table.getBackground().getRGB(),
        table.getForeground().getRGB(),
        table.getHeaderBackground().getRGB(),
        table.getHeaderForeground().getRGB());
  }

  private List<RGB> fieldColors() {
    return List.of(field.getBackground().getRGB(), field.getForeground().getRGB());
  }

  /** Creates a platform's colors for the surface and the icons after a light or dark start. */
  private ChromeColors chrome(PlatformTraits traits, boolean nativeDark) {
    display.setData(DialogColors.NATIVE_DARK, nativeDark);
    ChromeColors chrome = traits.chromeColors(display);
    chrome.add(surface);
    chrome.add(icons);
    return chrome;
  }

  private static boolean darkInk(ToolItem item) {
    return ToolIcons.CHECKED_TEXT.equals(item.getForeground().getRGB());
  }

  /**
   * After a dark start a win32 field holding text stays dark on the light side, whether the window
   * creates its chrome before the theme service records the start ({@code Win32DarkBrowserTest}'s
   * order) or after it ({@code Gui}'s). Read once at construction, the first order took a light
   * start and turned the field white under the dark native field's near-white text (CI on {@code
   * 92bc3f8}).
   */
  @Test
  void aFieldHoldingTextStaysDarkWhenTheServiceRecordsTheDarkStartAfterTheChrome() {
    darkStartKeepsAFieldHoldingTextDark(true);
  }

  @Test
  void aFieldHoldingTextStaysDarkWhenTheServiceRecordsTheDarkStartBeforeTheChrome() {
    darkStartKeepsAFieldHoldingTextDark(false);
  }

  private void darkStartKeepsAFieldHoldingTextDark(boolean chromeFirst) {
    ChromeColors chrome = chromeFirst ? WIN32.chromeColors(display) : null;
    try {
      SwtThemeService.open(display, ThemeMode.DARK, WIN32.themeFollow(display));
      if (chrome == null) {
        chrome = WIN32.chromeColors(display);
      }
      chrome.add(surface);
      chrome.apply(true);
      field.setText("*.md");
      chrome.apply(false);
      assertEquals(FIELD, field.getBackground().getRGB());
      assertEquals(TEXT, field.getForeground().getRGB());
      field.setText("");
      assertNotEquals(FIELD, field.getBackground().getRGB());
    } finally {
      if (chrome != null) {
        chrome.dispose();
      }
      display.setDarkThemePreferred(false);
    }
  }

  /**
   * Where a tree's or table's side differs from the native start the selected row is drawn in the
   * palette's selection colors at 4.5:1 or more; the native tree kept its startup selection, 1.66:1
   * after a dark start on the light side and 1.39:1 after a light start on the dark side (OBERON
   * G1).
   */
  @Test
  void aSelectedRowOnTheOtherSideIsDrawnReadable() {
    boolean[][] cases = {{true, false}, {false, true}};
    for (boolean[] start : cases) {
      boolean nativeDark = start[0];
      boolean dark = start[1];
      List<Composite> lists =
          List.<Composite>of(new Tree(surface, SWT.NONE), new Table(surface, SWT.NONE));
      ChromeColors chrome = chrome(WIN32, nativeDark);
      try {
        chrome.apply(true);
        chrome.apply(dark);
        for (Composite list : lists) {
          String what = list + ", start " + nativeDark;
          Event selected = eraseSelected(list);
          assertEquals(0, selected.detail & SWT.SELECTED, what);
          double ratio =
              Contrast.ratio(
                  selected.gc.getForeground().getRGB(), selected.gc.getBackground().getRGB());
          assertTrue(ratio >= 4.5, what + ": " + ratio);
          selected.gc.dispose();
        }
        chrome.apply(nativeDark);
        for (Composite list : lists) {
          Event nativeSide = eraseSelected(list);
          assertEquals(SWT.SELECTED, nativeSide.detail & SWT.SELECTED, list + ", native side");
          nativeSide.gc.dispose();
        }
      } finally {
        chrome.dispose();
      }
    }
  }

  /** Sends a list an {@code SWT.EraseItem} for a selected row, on a GC the caller disposes. */
  private static Event eraseSelected(Composite list) {
    Event event = new Event();
    event.gc = new GC(list);
    event.detail = SWT.SELECTED | SWT.FOREGROUND | SWT.BACKGROUND;
    event.x = 0;
    event.y = 0;
    event.width = 20;
    event.height = 10;
    list.notifyListeners(SWT.EraseItem, event);
    return event;
  }

  @Test
  void aDarkThemeOnWin32ColorsTheSurfacesAndKeepsNativeControls() {
    chrome(WIN32, false).apply(true);
    for (Control control : painted()) {
      assertEquals(BACKGROUND, control.getBackground().getRGB(), "" + control);
      assertEquals(TEXT, control.getForeground().getRGB(), "" + control);
    }
    for (Control control : kept) {
      assertEquals(OWN_BACKGROUND, control.getBackground().getRGB(), "" + control);
      assertEquals(OWN_TEXT, control.getForeground().getRGB(), "" + control);
    }
    assertEquals(SWT.INHERIT_DEFAULT, surface.getBackgroundMode());
    assertTrue(Contrast.ratio(link.getLinkForeground().getRGB(), BACKGROUND) >= Contrast.LINK);
  }

  @Test
  void aDarkThemeOnWin32ColorsTheFieldsTheTreeAndTheTable() {
    chrome(WIN32, false).apply(true);
    assertEquals(List.of(FIELD, TEXT), fieldColors());
    assertEquals(
        List.of(FIELD, TEXT, BACKGROUND, TEXT, FIELD, TEXT, BACKGROUND, TEXT), treeColors());
  }

  /** A read-only combo, whose list the theme leaves white, takes the button face under dark. */
  @Test
  void aReadOnlyComboTakesTheButtonFaceUnderDark() {
    Combo readOnly = new Combo(surface, SWT.READ_ONLY);
    RGB background = readOnly.getBackground().getRGB();
    RGB foreground = readOnly.getForeground().getRGB();
    ChromeColors chrome = chrome(WIN32, false);
    for (boolean dark : new boolean[] {true, false, true}) {
      chrome.apply(dark);
      assertEquals(dark ? FACE : background, readOnly.getBackground().getRGB(), "dark " + dark);
      assertEquals(dark ? TEXT : foreground, readOnly.getForeground().getRGB(), "dark " + dark);
    }
  }

  @Test
  void aLiveSwitchRestoresTheSystemColorsAndPaintsAgain() {
    List<RGB> system = colors(painted());
    List<RGB> systemField = fieldColors();
    List<RGB> systemTree = treeColors();
    ChromeColors chrome = chrome(WIN32, false);
    field.setText("mock");
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      if (dark) {
        assertEquals(BACKGROUND, surface.getBackground().getRGB());
        assertEquals(List.of(FIELD, TEXT), fieldColors());
      } else {
        assertEquals(system, colors(painted()));
        assertEquals(systemField, fieldColors());
        assertEquals(systemTree, treeColors());
        assertEquals(SWT.INHERIT_NONE, surface.getBackgroundMode());
      }
    }
  }

  /**
   * After a dark start a field holding text stays dark on the light side, while the tree follows
   * the theme; an empty field, such as the path field with no workspace folder, takes the system
   * list colors, set explicitly. With no colors the find and replace fields drew the dark native
   * field, {@code #212121} (Windows 11, OBERON G1).
   */
  @Test
  void afterADarkStartOnlyAFieldHoldingTextStaysDarkOnTheLightSide() {
    List<RGB> systemField =
        List.of(
            display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB(),
            display.getSystemColor(SWT.COLOR_LIST_FOREGROUND).getRGB());
    List<RGB> systemTree = treeColors();
    ChromeColors chrome = chrome(WIN32, true);
    chrome.apply(true);
    chrome.apply(false);
    assertEquals(systemField, fieldColors());
    assertEquals(systemTree, treeColors());
    field.setText("mock");
    assertEquals(List.of(FIELD, TEXT), fieldColors(), "takes text");
    field.setText("");
    assertEquals(systemField, fieldColors(), "empties");
  }

  /**
   * After a dark start an empty, unfocused field is painted in its background, the system one on
   * the light side and the field gray under dark, with its hint at 4.5:1 or more: the dark native
   * field drew {@code #212121} there under any colors (find and replace fields, Windows 11, OBERON
   * G1). A field holding text keeps the native drawing.
   */
  @Test
  void afterADarkStartAnEmptyFieldIsPaintedInItsBackground() {
    Text find = new Text(surface, SWT.SINGLE);
    field.setSize(200, 30);
    find.setSize(200, 30);
    RGB system = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
    ChromeColors chrome = chrome(WIN32, true);
    chrome.apply(true);
    chrome.apply(false);
    ImageData hinted = TextPaint.paint(field, OWN_BACKGROUND);
    assertEquals(system, TextPaint.at(hinted, 1, 1));
    assertNotEquals(system, TextPaint.farthest(hinted, system), "the hint draws");
    RGB ink = TextPaint.ink(field, OWN_BACKGROUND);
    assertTrue(Contrast.ratio(ink, system) >= 4.5, "hint " + ink);
    ImageData plain = TextPaint.paint(find, OWN_BACKGROUND);
    assertEquals(system, TextPaint.at(plain, 1, 1));
    assertEquals(system, TextPaint.farthest(plain, system), "no hint");
    find.setText("mock");
    assertEquals(OWN_BACKGROUND, TextPaint.at(TextPaint.paint(find, OWN_BACKGROUND), 1, 1));
    chrome.apply(true);
    ImageData dark = TextPaint.paint(field, OWN_BACKGROUND);
    assertEquals(FIELD, TextPaint.at(dark, dark.width - 2, dark.height - 2), "dark side");
    assertEquals(HINT, TextPaint.ink(field, OWN_BACKGROUND), "dark hint");
    assertTrue(Contrast.ratio(HINT, FIELD) >= 4.5);
  }

  /** After a light start an empty field is painted only under dark; the light side stays native. */
  @Test
  void aLightStartPaintsAnEmptyFieldUnderDarkOnly() {
    field.setSize(200, 30);
    ChromeColors chrome = chrome(WIN32, false);
    chrome.apply(true);
    ImageData dark = TextPaint.paint(field, OWN_BACKGROUND);
    assertEquals(FIELD, TextPaint.at(dark, dark.width - 2, dark.height - 2), "dark side");
    chrome.apply(false);
    ImageData drawn = TextPaint.paint(field, OWN_BACKGROUND);
    assertEquals(OWN_BACKGROUND, TextPaint.at(drawn, drawn.width - 2, drawn.height - 2));
  }

  @Test
  void aLightStartLeavesAFieldHoldingTextToTheSystemOnTheLightSide() {
    List<RGB> systemField = fieldColors();
    ChromeColors chrome = chrome(WIN32, false);
    chrome.apply(true);
    field.setText("mock");
    chrome.apply(false);
    assertEquals(systemField, fieldColors());
  }

  @Test
  void aLightThemeAndOtherPlatformsChangeNothing() {
    List<RGB> system = colors(painted());
    List<RGB> systemField = fieldColors();
    List<RGB> systemTree = treeColors();
    chrome(WIN32, false).apply(false);
    for (PlatformTraits traits :
        List.of(
            PlatformTraits.of("cocoa", false),
            PlatformTraits.of("gtk", false),
            PlatformTraits.of("gtk", true))) {
      for (boolean nativeDark : new boolean[] {false, true}) {
        chrome(traits, nativeDark).apply(true);
      }
    }
    assertEquals(system, colors(painted()));
    assertEquals(systemField, fieldColors());
    assertEquals(systemTree, treeColors());
    assertEquals("filter", field.getMessage());
    assertEquals(SWT.INHERIT_NONE, surface.getBackgroundMode());
    assertFalse(icons.darkBackground());
    assertFalse(darkInk(source));
    for (ToolItem item : items) {
      assertNull(item.getDisabledImage());
    }
  }

  @Test
  void aControlCreatedLaterUnderASurfaceTakesTheColors() {
    chrome(WIN32, false).apply(true);
    final Label added = new Label(surface, SWT.NONE);
    final Text addedField = new Text(surface, SWT.SINGLE);
    final Label inTabs = new Label((Composite) kept.get(4), SWT.NONE);
    final RGB tabsLabel = inTabs.getForeground().getRGB();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return added.getForeground().getRGB().equals(TEXT)
                    && addedField.getBackground().getRGB().equals(FIELD);
              }
            },
            BOUND));
    assertEquals(BACKGROUND, added.getBackground().getRGB());
    assertEquals(tabsLabel, inTabs.getForeground().getRGB());
  }

  /**
   * The icons take the light ink on the painted toolbar, the More button and the Markdown actions
   * button with its arrow included, and every item a faded copy as its disabled image; a light
   * switch returns the dark ink and SWT's own disabled image.
   */
  @Test
  void theIconsFollowThePaintedToolbar() {
    ChromeColors chrome = chrome(WIN32, false);
    chrome.apply(true);
    assertTrue(icons.darkBackground());
    for (ToolItem item : items) {
      Image disabled = item.getDisabledImage();
      assertNotNull(disabled, item.getToolTipText());
      IconContrast.assertInk(IconContrast.LIGHT_INK, item.getImage().getImageData());
      int full = IconContrast.maxAlpha(item.getImage().getImageData());
      assertEquals(full * 40 / 100, IconContrast.maxAlpha(disabled.getImageData()), 1, "faded");
    }
    chrome.apply(false);
    assertFalse(icons.darkBackground());
    for (ToolItem item : items) {
      assertNull(item.getDisabledImage());
      IconContrast.assertInk(IconContrast.DARK_INK, item.getImage().getImageData());
    }
  }

  /**
   * The disabled icons' contrast on the dark toolbar, enabled against SWT's disabled copy against
   * the faded copy. SWT's copy is no dimmer than the enabled icon, so a disabled item would look
   * enabled; the faded copy is dimmer and stays readable.
   */
  @Test
  void theFadedCopyIsDimmerThanTheEnabledIconAndSwtsOwnIsNot() {
    chrome(WIN32, false).apply(true);
    double enabledMin = Double.MAX_VALUE;
    double swtMin = Double.MAX_VALUE;
    double fadedMin = Double.MAX_VALUE;
    double fadedMax = 0;
    for (ToolItem item : items) {
      ImageData enabled = item.getImage().getImageData();
      Image swtCopy = new Image(display, item.getImage(), SWT.IMAGE_DISABLE);
      try {
        double on = IconContrast.contrast(enabled, BACKGROUND);
        double swt = IconContrast.contrast(swtCopy.getImageData(), BACKGROUND);
        double faded = IconContrast.contrast(item.getDisabledImage().getImageData(), BACKGROUND);
        assertTrue(swt >= on, "SWT's copy " + swt + " against " + on);
        assertTrue(faded < on, "faded " + faded + " against " + on);
        enabledMin = Math.min(enabledMin, on);
        swtMin = Math.min(swtMin, swt);
        fadedMin = Math.min(fadedMin, faded);
        fadedMax = Math.max(fadedMax, faded);
      } finally {
        swtCopy.dispose();
      }
    }
    System.out.printf(
        "chrome icon contrast on %s: enabled min %.2f, SWT disabled min %.2f, faded %.2f-%.2f%n",
        BACKGROUND, enabledMin, swtMin, fadedMin, fadedMax);
    assertTrue(fadedMin >= IconContrast.DISABLED_MIN, "faded " + fadedMin);
  }

  /**
   * A checked text item draws the startup theme's checked background: after a light start and a
   * switch to dark it stays light ({@code #cce8ff}), so the checked item alone takes the dark ink,
   * following the check. After a dark start the checked background is dark ({@code #455160}) and
   * the item keeps the light text; on the light side every item keeps the system text.
   */
  @Test
  void aCheckedItemTakesTheInkOfItsStartupCheckedBackground() {
    ChromeColors light = chrome(WIN32, false);
    assertFalse(darkInk(source), "light start, light");
    light.apply(true);
    assertTrue(darkInk(source), "light start, dark");
    assertFalse(darkInk(preview));
    icons.check(source, false);
    icons.check(preview, true);
    assertFalse(darkInk(source), "unchecked");
    assertTrue(darkInk(preview), "checked");
    light.apply(false);
    assertFalse(darkInk(preview), "light start, back to light");
    light.dispose();
    ChromeColors dark = chrome(WIN32, true);
    dark.apply(true);
    assertFalse(darkInk(preview), "dark start, dark");
    dark.apply(false);
    assertFalse(darkInk(preview), "dark start, light");
    RGB darkText = ToolIcons.CHECKED_TEXT;
    assertTrue(Contrast.ratio(darkText, CHECKED_LIGHT_START) >= Contrast.LINK);
    assertTrue(Contrast.ratio(TEXT, CHECKED_DARK_START) >= Contrast.LINK);
    assertTrue(Contrast.ratio(TEXT, CHECKED_LIGHT_START) < Contrast.LINK);
  }

  @Test
  void aDisposedSurfaceLeavesTheList() {
    ChromeColors chrome = chrome(WIN32, false);
    Composite other = new Composite(shell, SWT.NONE);
    chrome.add(other);
    surface.dispose();
    chrome.apply(true);
    assertEquals(BACKGROUND, other.getBackground().getRGB());
  }
}
