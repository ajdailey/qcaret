package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.ui.swt.platform.RunningPlatform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main toolbar in a narrow window: the buttons past the window's edge are listed, in toolbar
 * order, under a More button that shows only while one is hidden, and no button shows cut; each
 * entry carries its button's checked and enabled state and runs the button, for a plain tab and a
 * Markdown tab. The row keeps its height across tabs.
 */
@Tag("swt")
class ToolbarOverflowSurfaceTest {
  private static final String MOCK_REASON = "mock browser failure";

  @TempDir Path temp;

  private MarkdownWindow w;
  private ToolbarOverflow row;

  /** A row width that shows every plain-tab button. */
  private static final int WIDE = 900;

  @BeforeEach
  void open() {
    w = new MarkdownWindow(temp);
    row = w.window.toolbarRow();
  }

  @AfterEach
  void close() {
    w.close();
  }

  /**
   * Sizes the toolbar row inside a shell with no layout and waits until the More button matches the
   * width. It sizes the row, not the shell: GTK 3 can apply a shell size late and then restore the
   * earlier one, after the shell has reported the new size. At a fractional zoom the width reads
   * back within a point ({@link ScaleSlack}).
   */
  private void width(final int width) {
    w.window.shell().setLayout(null);
    final Composite bar = row.row();
    bar.setSize(width, bar.getSize().y);
    final int slack = ScaleSlack.of(bar);
    w.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            int preferred = row.bar().computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
            return Math.abs(bar.getSize().x - width) <= slack
                && row.moreShown() == preferred > bar.getClientArea().width;
          }
        },
        "row width " + width);
    w.flush();
  }

  /**
   * Sizes the row so that a button and every one after it are hidden at any scale: the row ends at
   * the button's left edge as a row twice {@link #WIDE} places it.
   */
  private void hideFrom(CommandId command) {
    width(2 * WIDE);
    ToolItem button = null;
    for (ToolItem item : row.bar().getItems()) {
      if (item.getData() == command) {
        button = item;
      }
    }
    assertNotNull(button, command + " on the row");
    Point left = row.row().toControl(row.bar().toDisplay(button.getBounds().x, 0));
    width(left.x);
  }

  /**
   * Asserts that every shown button ends within the clip, that the clip shows no part of a hidden
   * one, and that the first hidden one would not fit beside the More button.
   */
  private void assertNoneCut(int width) {
    List<ToolItem> hidden = row.hidden();
    int shown = row.shownWidth();
    int slack = ScaleSlack.of(row.row());
    ToolItem firstHidden = null;
    for (ToolItem item : row.bar().getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        continue;
      }
      Rectangle bounds = item.getBounds();
      if (hidden.contains(item)) {
        if (firstHidden == null) {
          firstHidden = item;
        }
        continue;
      }
      assertTrue(firstHidden == null, "a shown button after a hidden one at " + width);
      assertTrue(
          bounds.x + bounds.width <= shown + slack,
          "cut at " + width + ": " + bounds + " in " + shown);
    }
    assertEquals(firstHidden != null, row.moreShown(), "More at " + width);
    if (firstHidden != null) {
      Rectangle bounds = firstHidden.getBounds();
      assertTrue(shown <= bounds.x + slack, "hidden but drawn at " + width + ": " + bounds);
      // The width the layout reserves; GTK 4 can report the More button's size late.
      int more = row.more().getParent().computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
      int room = row.row().getClientArea().width - more;
      assertTrue(
          bounds.x + bounds.width > room - slack,
          "hidden though it fits at " + width + ": " + bounds + " in " + room);
    }
  }

  /** Returns the data of every button: its command, or a drop-down's menu, in toolbar order. */
  private List<Object> buttons() {
    List<Object> buttons = new ArrayList<Object>();
    for (ToolItem item : row.bar().getItems()) {
      if (item.getData() instanceof CommandId || item.getData() instanceof ToolMenu) {
        buttons.add(item.getData());
      }
    }
    return buttons;
  }

  /** Returns the shown buttons' data, then the More menu's, in order. */
  private List<Object> reachable(Menu menu) {
    List<Object> buttons = new ArrayList<Object>();
    List<ToolItem> hidden = row.hidden();
    for (ToolItem item : row.bar().getItems()) {
      if ((item.getData() instanceof CommandId || item.getData() instanceof ToolMenu)
          && !hidden.contains(item)) {
        buttons.add(item.getData());
      }
    }
    for (MenuItem entry : menu.getItems()) {
      buttons.add(entry.getData());
    }
    return buttons;
  }

  /** Returns a command's entry in a menu or its cascades. */
  private MenuItem entry(Menu menu, CommandId command) {
    for (MenuItem entry : menu.getItems()) {
      if (entry.getData() == command) {
        return entry;
      }
      if (entry.getMenu() != null) {
        MenuItem found = entry(entry.getMenu(), command);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private void choose(MenuItem entry) {
    entry.notifyListeners(SWT.Selection, new Event());
    w.flush();
  }

  @Test
  void aNarrowWindowListsTheHiddenButtonsAndAWideOneHidesTheButton() throws Exception {
    w.open("notes.txt", "plain\n", false);
    width(WIDE);
    assertFalse(row.moreShown());
    assertTrue(row.hidden().isEmpty());
    width(260);
    assertTrue(row.moreShown());
    assertFalse(row.hidden().isEmpty());
    assertNoneCut(260);
    int client = row.row().getClientArea().width;
    assertTrue(
        row.more().getParent().getBounds().x + row.more().getParent().getBounds().width <= client);
    Menu menu = row.menu();
    assertEquals(buttons(), reachable(menu));
    MenuItem last = menu.getItem(menu.getItemCount() - 1);
    assertEquals(CommandId.ZOOM_OUT, last.getData());
    if (!RunningPlatform.COCOA && !"1".equals(System.getenv("SWT_GTK4"))) {
      assertNotNull(last.getImage());
    }
    width(WIDE);
    assertFalse(row.moreShown());
    assertTrue(row.hidden().isEmpty());
  }

  @Test
  void noButtonShowsCutAtAnyWidth() throws Exception {
    w.open("guide.md", "# Mock guide\n", true);
    for (int width = 160; width <= WIDE; width += 23) {
      width(width);
      assertNoneCut(width);
      assertEquals(buttons(), reachable(row.menu()), "reachable at " + width);
    }
  }

  @Test
  void switchingTabsKeepsTheTabStripInPlace() throws Exception {
    w.open("notes.txt", "plain\n", false);
    final int plain = w.window.tabs().getItemCount() - 1;
    w.flush();
    int plainY = w.window.tabs().toDisplay(0, 0).y;
    int plainRow = row.row().getSize().y;
    w.open("guide.md", "# Mock guide\n", true);
    final int markdown = w.window.tabs().getItemCount() - 1;
    assertNotNull(w.window.markdownSwitch().items()[0], "the switch shows");
    w.flush();
    assertEquals(plainRow, row.row().getSize().y, "row height on a Markdown tab");
    assertEquals(plainY, w.window.tabs().toDisplay(0, 0).y, "tab strip on a Markdown tab");
    w.controller.documents().select(plain);
    w.flush();
    assertNull(w.window.markdownSwitch().items()[0], "the switch hides");
    assertEquals(plainY, w.window.tabs().toDisplay(0, 0).y, "tab strip back on a plain tab");
    w.controller.documents().select(markdown);
    w.flush();
    assertEquals(plainY, w.window.tabs().toDisplay(0, 0).y, "tab strip on the Markdown tab again");
  }

  /**
   * A click on More shows its menu with the hidden buttons: a posted click on GTK and win32, and
   * the button's Selection on cocoa, where posted pointer events end the test JVM. The click waits
   * until More has its place: GTK 4 reports a newly shown toolbar at {0, 0, 0, 0} for a while, and
   * a click at its mapped center landed on the main toolbar. It skips where no control lies under
   * the posted pointer, as on the windows-aarch64 runner at every point measured. The menu closes
   * from its Show event.
   */
  @Test
  void aClickOnMoreShowsTheHiddenButtons() throws Exception {
    w.open("notes.txt", "plain\n", false);
    width(260);
    final int hidden = row.hidden().size();
    final List<Integer> shown = new ArrayList<Integer>();
    final List<String> selected = new ArrayList<String>();
    Listener show =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Menu menu && (menu.getStyle() & SWT.POP_UP) != 0) {
              shown.add(menu.getItemCount());
              menu.setVisible(false);
            }
          }
        };
    row.more()
        .addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                selected.add("selected");
              }
            });
    w.display.addFilter(SWT.Show, show);
    try {
      final ToolItem more = row.more();
      String under = "";
      if (RunningPlatform.COCOA) {
        more.notifyListeners(SWT.Selection, new Event());
      } else {
        w.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return placed(more);
              }
            },
            "More placed: " + more.getParent().getBounds() + " " + more.getBounds());
        w.window.shell().forceActive();
        w.flush();
        PostedInput.click(w.display, center(more));
        Control cursor = w.display.getCursorControl();
        assumeTrue(cursor != null, "the posted pointer reaches no control");
        under = cursor.toString();
      }
      final String diagnosis =
          "More "
              + more.getParent().getBounds()
              + " at "
              + center(more)
              + ", pointer "
              + w.display.getCursorLocation()
              + " under "
              + under
              + ", shell "
              + w.window.shell().getBounds()
              + " active "
              + (w.display.getActiveShell() == w.window.shell())
              + ", monitor "
              + w.window.shell().getMonitor().getBounds()
              + " zoom "
              + w.window.shell().getMonitor().getZoom();
      w.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !shown.isEmpty();
            }
          },
          "More menu shown; selected " + selected + ", " + diagnosis);
      assertEquals(List.of(hidden), shown, diagnosis);
    } finally {
      w.display.removeFilter(SWT.Show, show);
    }
  }

  /** Returns whether More has its place in the row and maps its points from there. */
  private boolean placed(ToolItem more) {
    Rectangle button = more.getParent().getBounds();
    return button.x > 0
        && more.getBounds().width > 0
        && more.getParent().toDisplay(0, 0).equals(row.row().toDisplay(button.x, button.y));
  }

  /** Returns the center of More in display points, mapped from the row. */
  private Point center(ToolItem more) {
    Rectangle button = more.getParent().getBounds();
    Rectangle bounds = more.getBounds();
    return row.row()
        .toDisplay(button.x + bounds.x + bounds.width / 2, button.y + bounds.y + bounds.height / 2);
  }

  @Test
  void aMenuEntryRunsItsButton() throws Exception {
    w.open("notes.txt", "plain\n", false);
    width(260);
    int before = w.options().fontZoom();
    choose(entry(row.menu(), CommandId.ZOOM_IN));
    assertTrue(w.options().fontZoom() != before);
  }

  @Test
  void aMarkdownTabListsItsModesAndTogglesInStepWithTheButtons() throws Exception {
    w.open("notes.txt", "plain\n", false);
    final int plain = w.window.tabs().getItemCount() - 1;
    w.open("guide.md", "# Mock guide\n", true);
    hideFrom(CommandId.MARKDOWN_SOURCE);
    assertTrue(row.moreShown());
    Menu menu = row.menu();
    assertEquals(buttons(), reachable(menu));
    MenuItem split = entry(menu, CommandId.MARKDOWN_SPLIT);
    assertNotNull(split);
    assertTrue((split.getStyle() & SWT.RADIO) != 0);
    assertTrue(entry(menu, CommandId.MARKDOWN_SOURCE).getSelection());
    choose(split);
    assertEquals(MarkdownViewMode.SPLIT, w.controller.documents().markdownMode());
    assertTrue(w.window.markdownSwitch().items()[1].getSelection());
    assertFalse(w.window.markdownSwitch().items()[0].getSelection());
    menu = row.menu();
    assertTrue(entry(menu, CommandId.MARKDOWN_SPLIT).getSelection());
    assertFalse(entry(menu, CommandId.MARKDOWN_SOURCE).getSelection());

    MenuItem actions = menu.getItem(menu.getItemCount() - 1);
    assertTrue(actions.getData() instanceof ToolMenu);
    assertNotNull(actions.getMenu());
    MenuItem sync = entry(menu, CommandId.MARKDOWN_SYNC_SCROLL);
    assertTrue((sync.getStyle() & SWT.CHECK) != 0);
    assertTrue(sync.getSelection());
    choose(sync);
    assertFalse(w.options().markdownSyncScroll());
    assertFalse(entry(row.menu(), CommandId.MARKDOWN_SYNC_SCROLL).getSelection());

    w.controller.documents().select(plain);
    w.flush();
    for (MenuItem entry : row.menu().getItems()) {
      assertFalse(entry.getData() == CommandId.MARKDOWN_SPLIT, "a plain tab lists no mode");
    }
  }

  @Test
  void aDisabledButtonIsDisabledInTheMenuWithItsReason() throws Exception {
    BrowserPreview.failed(w.display, MOCK_REASON);
    String reason = w.window.previewBrowserUnavailable();
    w.open("guide.md", "# Mock guide\n", true);
    w.window.documentStateChanged();
    hideFrom(CommandId.MARKDOWN_SOURCE);
    MenuItem browser = entry(row.menu(), CommandId.PREVIEW_BROWSER);
    assertNotNull(browser);
    assertFalse(browser.getEnabled());
    String shown = !RunningPlatform.GTK4 ? browser.getToolTipText() : browser.getText();
    assertTrue(shown.contains(reason), shown);
  }
}
