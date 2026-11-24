package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link ChromeColors} with each platform's traits, on any platform. */
@Tag("swt")
class ChromeColorsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final PlatformTraits WIN32 = PlatformTraits.of("win32", false);
  private static final RGB OWN_BACKGROUND = new RGB(0x10, 0x20, 0x30);
  private static final RGB OWN_TEXT = new RGB(0x40, 0x50, 0x60);

  private Display display;
  private Shell shell;
  private Composite surface;
  private Label label;
  private Link link;
  private ToolBar bar;
  private Button check;
  private Canvas gauge;
  private List<Control> kept;
  private ToolIcons icons;
  private List<ToolItem> items;

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
    CTabFolder tabs = new CTabFolder(surface, SWT.NONE);
    Composite marked = new Composite(surface, SWT.NONE);
    marked.setData(EditorPane.PALETTE_COLORED, Boolean.TRUE);
    kept =
        List.of(
            new Button(surface, SWT.PUSH),
            new Button(surface, SWT.ARROW | SWT.DOWN),
            new Text(surface, SWT.SINGLE),
            new Combo(surface, SWT.DROP_DOWN),
            new Tree(surface, SWT.NONE),
            new StyledText(surface, SWT.NONE),
            tabs,
            new Label(tabs, SWT.NONE),
            marked,
            new Label(marked, SWT.NONE));
    icons = new ToolIcons(bar);
    items = new ArrayList<ToolItem>();
    for (String name : names()) {
      ToolItem item = new ToolItem(bar, SWT.PUSH);
      assertTrue(icons.apply(item, name), name);
      items.add(item);
    }
    items.add(icons.item(surface, ToolIcons.TAB_LIST_DOWN, "More"));
    // A kept control carries its own colors, as the editor, tab strip and fields do.
    for (Control control : kept) {
      control.setBackground(new Color(OWN_BACKGROUND));
      control.setForeground(new Color(OWN_TEXT));
    }
  }

  @AfterEach
  void dispose() {
    display.dispose();
  }

  /** Every icon name {@link ToolIcons} declares. */
  static List<String> names() {
    List<String> names = new ArrayList<String>();
    for (Field field : ToolIcons.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.getType() == String.class
          && Modifier.isStatic(modifiers)
          && !Modifier.isPrivate(modifiers)) {
        try {
          names.add((String) field.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    }
    return names;
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  private List<Control> painted() {
    return List.of(
        surface, label, link, bar, check, gauge, items.get(items.size() - 1).getParent());
  }

  private List<RGB> colors(List<Control> controls) {
    List<RGB> colors = new ArrayList<RGB>();
    for (Control control : controls) {
      colors.add(control.getBackground().getRGB());
      colors.add(control.getForeground().getRGB());
    }
    return colors;
  }

  private ChromeColors chrome(PlatformTraits traits) {
    ChromeColors chrome = new ChromeColors(traits, display);
    chrome.add(surface);
    chrome.add(icons);
    return chrome;
  }

  @Test
  void aDarkThemeOnWin32ColorsTheSurfacesAndKeepsNativeControls() {
    chrome(WIN32).apply(true);
    for (Control control : painted()) {
      assertEquals(rgb(ChromeColors.BACKGROUND), control.getBackground().getRGB(), "" + control);
      assertEquals(rgb(ChromeColors.TEXT), control.getForeground().getRGB(), "" + control);
    }
    for (Control control : kept) {
      assertEquals(OWN_BACKGROUND, control.getBackground().getRGB(), "" + control);
      assertEquals(OWN_TEXT, control.getForeground().getRGB(), "" + control);
    }
    assertEquals(SWT.INHERIT_DEFAULT, surface.getBackgroundMode());
    assertTrue(
        SwtThemeService.contrast(link.getLinkForeground().getRGB(), rgb(ChromeColors.BACKGROUND))
            >= 4.5);
  }

  @Test
  void aLiveSwitchRestoresTheSystemColorsAndPaintsAgain() {
    List<RGB> system = colors(painted());
    ChromeColors chrome = chrome(WIN32);
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      assertEquals(dark, chrome.dark());
      if (dark) {
        assertEquals(rgb(ChromeColors.BACKGROUND), surface.getBackground().getRGB());
      } else {
        assertEquals(system, colors(painted()));
        assertEquals(SWT.INHERIT_NONE, surface.getBackgroundMode());
      }
    }
  }

  @Test
  void aLightThemeAndOtherPlatformsChangeNothing() {
    List<RGB> system = colors(painted());
    chrome(WIN32).apply(false);
    for (PlatformTraits traits :
        List.of(
            PlatformTraits.of("cocoa", false),
            PlatformTraits.of("gtk", false),
            PlatformTraits.of("gtk", true))) {
      chrome(traits).apply(true);
    }
    assertEquals(system, colors(painted()));
    assertEquals(SWT.INHERIT_NONE, surface.getBackgroundMode());
    assertFalse(icons.darkBackground());
    for (ToolItem item : items) {
      assertNull(item.getDisabledImage());
    }
  }

  @Test
  void aControlCreatedLaterUnderASurfaceTakesTheColors() {
    chrome(WIN32).apply(true);
    final Label added = new Label(surface, SWT.NONE);
    final Label inTabs = new Label((Composite) kept.get(6), SWT.NONE);
    final RGB tabsLabel = inTabs.getForeground().getRGB();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return added.getForeground().getRGB().equals(rgb(ChromeColors.TEXT));
              }
            },
            BOUND));
    assertEquals(rgb(ChromeColors.BACKGROUND), added.getBackground().getRGB());
    assertEquals(tabsLabel, inTabs.getForeground().getRGB());
  }

  /**
   * The icons take the light ink on the painted toolbar, the More button and the Markdown actions
   * button with its arrow included, and every item a faded copy as its disabled image; a light
   * switch returns the dark ink and SWT's own disabled image.
   */
  @Test
  void theIconsFollowThePaintedToolbar() {
    ChromeColors chrome = chrome(WIN32);
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
    chrome(WIN32).apply(true);
    RGB ground = rgb(ChromeColors.BACKGROUND);
    double enabledMin = Double.MAX_VALUE;
    double swtMin = Double.MAX_VALUE;
    double fadedMin = Double.MAX_VALUE;
    double fadedMax = 0;
    for (ToolItem item : items) {
      ImageData enabled = item.getImage().getImageData();
      Image swtCopy = new Image(display, item.getImage(), SWT.IMAGE_DISABLE);
      try {
        double on = IconContrast.contrast(enabled, ground);
        double swt = IconContrast.contrast(swtCopy.getImageData(), ground);
        double faded = IconContrast.contrast(item.getDisabledImage().getImageData(), ground);
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
        ground, enabledMin, swtMin, fadedMin, fadedMax);
    assertTrue(fadedMin >= IconContrast.DISABLED_MIN, "faded " + fadedMin);
  }

  @Test
  void aDisposedSurfaceLeavesTheList() {
    ChromeColors chrome = chrome(WIN32);
    surface.dispose();
    chrome.apply(true);
    assertTrue(chrome.dark());
  }
}
