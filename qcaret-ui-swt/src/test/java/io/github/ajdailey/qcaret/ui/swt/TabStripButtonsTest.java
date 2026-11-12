package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.i18n.UiText;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The New, tab list and Close buttons at the end of the tab strip, with 30 tabs in a 700 px window:
 * one list control, the hidden count, the list menu, the wheel over the strip, the commands and the
 * icon ink across a theme switch. Events go to the controls through {@code notifyListeners}. {@code
 * qcaret.shots} names a folder for screenshots with the menu open, read from the screen, which GTK
 * 4 does not show.
 */
@Tag("swt")
class TabStripButtonsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int TABS = 30;
  private static final boolean GTK4 = "1".equals(System.getenv("SWT_GTK4"));

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow window;
  private ApplicationController controller;
  private CTabFolder tabs;
  private TabStripButtons buttons;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window = new MainWindow(display, Palette.LIGHT);
    controller = new ApplicationController(set, window);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 700, 500), null);
    window.open();
    List<Path> files = new ArrayList<Path>();
    for (int i = 0; i < TABS; i++) {
      files.add(
          Files.writeString(
              temp.resolve("file-" + (100 + i) + ".txt"), "line\n", StandardCharsets.US_ASCII));
    }
    controller.documents().open(files);
    tabs = window.tabs();
    buttons = window.tabButtons();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getItemCount() == TABS && buttons.hiddenCount() == hidden();
              }
            }),
        "tabs " + tabs.getItemCount() + ", count " + buttons.hiddenCount());
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private int hidden() {
    int count = 0;
    for (CTabItem item : tabs.getItems()) {
      if (!item.isShowing()) {
        count++;
      }
    }
    return count;
  }

  private int first() {
    for (int i = 0; i < tabs.getItemCount(); i++) {
      if (tabs.getItem(i).isShowing()) {
        return i;
      }
    }
    return -1;
  }

  private static void click(Canvas button) {
    for (int type : new int[] {SWT.MouseEnter, SWT.MouseDown, SWT.MouseUp}) {
      Event event = new Event();
      event.button = 1;
      event.x = 2;
      event.y = 2;
      button.notifyListeners(type, event);
    }
  }

  private void wheel(Control over, int count) {
    Event event = new Event();
    event.count = count;
    over.notifyListeners(SWT.MouseVerticalWheel, event);
  }

  private void select(final int index) {
    buttons.tabList().choose(tabs.getItem(index));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getSelectionIndex() == index && tabs.getItem(index).isShowing();
              }
            }));
  }

  private List<MenuItem> entries(Menu menu) {
    List<MenuItem> list = new ArrayList<MenuItem>();
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) == 0) {
        list.add(item);
      }
    }
    return list;
  }

  @Test
  void theThreeButtonsShowAndSwtsChevronTakesNoWidth() {
    assertTrue(hidden() > 0, "30 tabs overflow a 700 px window");
    assertTrue(buttons.control().isVisible());
    for (Canvas button : buttons.buttons()) {
      Point size = button.getSize();
      assertTrue(button.isVisible());
      assertTrue(size.x >= 16 && size.y >= 16, "button size " + size);
    }
    Rectangle group = buttons.control().getBounds();
    Rectangle last = null;
    for (CTabItem item : tabs.getItems()) {
      if (item.isShowing()) {
        last = item.getBounds();
      }
    }
    assertNotNull(last);
    assertTrue(last.x + last.width <= group.x, "tabs " + last + " end before " + group);
    System.out.printf(
        "Tab strip: last tab ends at %d, buttons %s, tab height %d%n",
        last.x + last.width, group, tabs.getTabHeight());
    for (Control child : tabs.getChildren()) {
      if (child instanceof ToolBar bar) {
        assertEquals(0, bar.getSize().x, "SWT's chevron shows: " + bar.getBounds());
      }
    }
  }

  @Test
  void theCountFollowsTheHiddenTabs() {
    int before = buttons.hiddenCount();
    assertTrue(before > 0);
    String tip = buttons.buttons().get(1).getToolTipText();
    assertEquals(UiText.TAB_LIST_HIDDEN.format(before), tip);
    window.shell().setSize(1400, 500);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return buttons.hiddenCount() == hidden() && buttons.hiddenCount() < before;
              }
            }),
        "count " + buttons.hiddenCount() + ", hidden " + hidden());
  }

  @Test
  void theMenuListsEveryTabAndAChoiceSelectsAndShowsIt() {
    select(TABS - 1);
    click(buttons.buttons().get(1));
    Menu menu = buttons.tabList().menu();
    menu.setVisible(false);
    List<MenuItem> entries = entries(menu);
    assertEquals(TABS, entries.size());
    for (int i = 0; i < TABS; i++) {
      assertEquals(tabs.getItem(i).getText(), entries.get(i).getText());
      assertEquals(i == TABS - 1, entries.get(i).getSelection());
    }
    assertTrue(menu.getItemCount() > TABS, "a separator sets the shown tabs apart");
    assertFalse(tabs.getItem(0).isShowing());
    MenuItem target = entries.get(0);
    entries.get(TABS - 1).setSelection(false);
    target.setSelection(true);
    target.notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getSelectionIndex() == 0
                    && tabs.getItem(0).isShowing()
                    && inside(window.activeText(), tabs.getItem(0).getControl());
              }
            }));
  }

  private static boolean inside(Control control, Control ancestor) {
    for (Control c = control; c != null; c = c.getParent()) {
      if (c == ancestor) {
        return true;
      }
    }
    return false;
  }

  @Test
  void aDuplicateNameShowsItsFolder() throws Exception {
    Path other = Files.createDirectory(temp.resolve("other"));
    Path copy = Files.writeString(other.resolve("file-100.txt"), "line\n");
    controller.documents().open(List.of(copy));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getItemCount() == TABS + 1;
              }
            }));
    click(buttons.buttons().get(1));
    Menu menu = buttons.tabList().menu();
    menu.setVisible(false);
    List<MenuItem> entries = entries(menu);
    List<String> duplicates = new ArrayList<String>();
    for (int i = 0; i < entries.size(); i++) {
      String tab = tabs.getItem(i).getText();
      if (tab.equals("file-100.txt")) {
        duplicates.add(entries.get(i).getText());
      } else {
        assertEquals(tab, entries.get(i).getText());
      }
    }
    assertEquals(2, duplicates.size());
    // The tab tool tip shows the opened document's real path.
    Path real = other.toRealPath();
    assertTrue(
        duplicates.contains("file-100.txt  (" + real.getParent() + ")"), duplicates.toString());
    assertTrue(duplicates.contains("file-100.txt  (" + real + ")"), duplicates.toString());
  }

  @Test
  void theWheelShowsAHiddenTabAndKeepsTheSelection() {
    select(15);
    final int start = first();
    assertTrue(start > 0, "tabs hidden left of the selection");
    wheel(tabs, 3);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first() == start - 1;
              }
            }),
        "first shown " + first());
    assertEquals(15, tabs.getSelectionIndex());
    assertTrue(tabs.getItem(15).isShowing());
    wheel(buttons.buttons().get(1), -3);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first() == start;
              }
            }),
        "first shown " + first());
    assertEquals(15, tabs.getSelectionIndex());
  }

  @Test
  void theWheelLeavesTheSelectedTabShown() {
    select(15);
    for (int i = 0; i < TABS; i++) {
      wheel(tabs, -3);
      runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return true;
            }
          });
    }
    assertEquals(15, first(), "the selected tab stays the first shown");
    assertTrue(tabs.getItem(15).isShowing());
  }

  @Test
  void newOpensATab() {
    click(buttons.buttons().get(0));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                CTabItem selected = tabs.getSelection();
                return tabs.getItemCount() == TABS + 1
                    && selected.isShowing()
                    && selected.getText().equals(UiText.TAB_UNTITLED.format(1));
              }
            }),
        "tabs "
            + tabs.getItemCount()
            + ", selected "
            + tabs.getSelection().getText()
            + " shown "
            + tabs.getSelection().isShowing()
            + " at "
            + tabs.getSelectionIndex());
  }

  /**
   * A new tab is shown at every window width. {@code CTabFolder} appends a new item last in its
   * show order; when the selected new tab fits the run already shown, the folder kept that order,
   * measured no width for the tab and left it hidden (macOS CI at 700 px).
   */
  @Test
  void aNewTabShowsAtEveryWidth() {
    Point size = window.shell().getSize();
    List<Integer> hiddenAt = new ArrayList<Integer>();
    for (int width = 600; width <= 720; width += 3) {
      window.shell().setSize(width, size.y);
      controller.execute(CommandId.NEW);
      final int expected = TABS + 1;
      runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return tabs.getItemCount() == expected;
            }
          });
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return tabs.getSelection().isShowing();
            }
          },
          Duration.ofSeconds(2));
      if (!tabs.getSelection().isShowing()) {
        hiddenAt.add(width);
      }
      controller.execute(CommandId.CLOSE);
      runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return tabs.getItemCount() == TABS;
            }
          });
    }
    assertTrue(hiddenAt.isEmpty(), "new tab hidden at widths " + hiddenAt);
  }

  @Test
  void closeAsksAboutUnsavedChanges() {
    final String name = tabs.getSelection().getText();
    window.activeText().insert("Q");
    assertTrue(controller.documents().activeEditor().document().isDirty());
    click(buttons.buttons().get(2));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return prompt() != null;
              }
            }),
        "the unsaved prompt opens");
    prompt().close();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return prompt() == null;
              }
            }));
    assertEquals(TABS, tabs.getItemCount(), "Cancel keeps the tab");
    controller.execute(CommandId.UNDO);
    assertFalse(controller.documents().activeEditor().document().isDirty());
    click(buttons.buttons().get(2));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getItemCount() == TABS - 1;
              }
            }),
        "a clean tab closes at once");
    for (CTabItem item : tabs.getItems()) {
      assertNotEquals(name, item.getText());
    }
  }

  private Shell prompt() {
    for (Shell shell : window.shell().getShells()) {
      if (!shell.isDisposed() && UiText.UNSAVED_TITLE.text().equals(shell.getText())) {
        return shell;
      }
    }
    return null;
  }

  /** Returns the list button's pixels through {@code Control.print}, or null where it fails. */
  private ImageData printed() {
    Canvas list = buttons.buttons().get(1);
    Point size = list.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      return list.print(gc) ? image.getImageData() : null;
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns the luminance of the background pixel and of the pixel farthest from it. */
  private static double[] inkAndBackground(ImageData data) {
    RGB background = data.palette.getRGB(data.getPixel(1, 1));
    double base = luminance(background);
    double farthest = base;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        double l = luminance(data.palette.getRGB(data.getPixel(x, y)));
        if (Math.abs(l - base) > Math.abs(farthest - base)) {
          farthest = l;
        }
      }
    }
    return new double[] {farthest, base};
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double c = channel / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static double contrast(double[] pair) {
    double hi = Math.max(pair[0], pair[1]);
    double lo = Math.min(pair[0], pair[1]);
    return (hi + 0.05) / (lo + 0.05);
  }

  private double[] settledInk(final boolean dark) {
    final double[][] seen = new double[1][];
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            ImageData data = printed();
            if (data == null) {
              return true;
            }
            seen[0] = inkAndBackground(data);
            return dark ? seen[0][0] > seen[0][1] : seen[0][0] < seen[0][1];
          }
        });
    return seen[0];
  }

  @Test
  void theIconsFollowALiveThemeSwitch() {
    assumeTrue(printed() != null, "Control.print reads no pixels here");
    ScreenProbe.assumeDrawn(
        printed(),
        1,
        1,
        buttons.buttons().get(1).getBackground().getRGB(),
        "the list button's print");
    assertFalse(buttons.lightInk());
    double[] light = settledInk(false);
    assertTrue(light[0] < light[1], "dark ink on the light strip");
    controller.setTheme(ThemeMode.DARK);
    assertTrue(buttons.lightInk());
    double[] dark = settledInk(true);
    assumeTrue(dark != null, "Control.print reads no pixels after the switch");
    assertTrue(dark[0] > dark[1], "light ink on the dark strip");
    controller.setTheme(ThemeMode.LIGHT);
    assertFalse(buttons.lightInk());
    double[] back = settledInk(false);
    assumeTrue(back != null, "Control.print reads no pixels after the switch");
    assertTrue(back[0] < back[1], "dark ink again");
    System.out.printf(
        "Tab strip icon contrast: light %.2f, dark %.2f%n", contrast(light), contrast(dark));
  }

  @Test
  void screenshotsWithTheMenuOpen() {
    String folder = System.getProperty("qcaret.shots");
    assumeTrue(folder != null && !GTK4, "qcaret.shots unset, or GTK 4");
    for (ThemeMode mode : new ThemeMode[] {ThemeMode.LIGHT, ThemeMode.DARK}) {
      controller.setTheme(mode);
      for (TabStripButtons.ListGlyph glyph : TabStripButtons.ListGlyph.values()) {
        buttons.setListGlyph(glyph);
        select(15);
        click(buttons.buttons().get(1));
        pause();
        String name = "tab-list-" + glyph.name().toLowerCase() + "-" + mode.name().toLowerCase();
        shoot(folder, name + ".png");
        buttons.tabList().menu().setVisible(false);
      }
    }
  }

  private void pause() {
    runUntil(
        new BooleanSupplier() {
          private final long end = System.nanoTime() + 700_000_000L;

          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > end;
          }
        });
  }

  private void shoot(String folder, String name) {
    Rectangle area = window.shell().getBounds();
    Image image = new Image(display, area.width, area.height + 320);
    GC gc = new GC(display);
    gc.copyArea(image, area.x, area.y);
    gc.dispose();
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(Path.of(folder, name).toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
