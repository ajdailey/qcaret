package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
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
 * The main toolbar in a narrow window: the buttons past the window's edge are listed, in toolbar
 * order, under a More button that shows only while one is hidden; each entry carries its button's
 * checked and enabled state and runs the button, for a plain tab and a Markdown tab.
 */
@Tag("swt")
class ToolbarOverflowSurfaceTest {
  private static final String MOCK_REASON = "mock browser failure";

  @TempDir Path temp;

  private MarkdownWindow w;
  private ToolbarOverflow row;

  @BeforeEach
  void open() {
    w = new MarkdownWindow(temp);
    row = w.window.toolbarRow();
  }

  @AfterEach
  void close() {
    w.close();
  }

  private void width(final int width) {
    w.window.shell().setSize(width, 500);
    w.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return w.window.shell().getSize().x == width
                && row.row().getSize().x == w.window.shell().getClientArea().width;
          }
        },
        "width " + width);
    w.flush();
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
    width(1200);
    assertFalse(row.moreShown());
    assertTrue(row.hidden().isEmpty());
    width(260);
    assertTrue(row.moreShown());
    assertFalse(row.hidden().isEmpty());
    int client = w.window.shell().getClientArea().width;
    assertTrue(
        row.more().getParent().getBounds().x + row.more().getParent().getBounds().width <= client);
    Menu menu = row.menu();
    assertEquals(buttons(), reachable(menu));
    MenuItem last = menu.getItem(menu.getItemCount() - 1);
    assertEquals(CommandId.ZOOM_OUT, last.getData());
    if (!"1".equals(System.getenv("SWT_GTK4"))) {
      assertNotNull(last.getImage());
    }
    width(1200);
    assertFalse(row.moreShown());
    assertTrue(row.hidden().isEmpty());
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
    width(420);
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
    width(420);
    MenuItem browser = entry(row.menu(), CommandId.PREVIEW_BROWSER);
    assertNotNull(browser);
    assertFalse(browser.getEnabled());
    String shown =
        PlatformTraits.PLATFORM.showsMenuToolTips() ? browser.getToolTipText() : browser.getText();
    assertTrue(shown.contains(reason), shown);
  }
}
