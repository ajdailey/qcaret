package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.PreviewRenderer;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.RunningPlatform;
import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
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
 * The Markdown actions drop-down after {@code Source | Split | Preview} and the status bar's
 * renderer popup: the drop-down is present for a Markdown tab only, its menu items follow and run
 * their commands, and Browser is disabled with its reason where it cannot run.
 */
@Tag("swt")
class MarkdownToolbarSurfaceTest {
  private static final String MOCK_REASON = "mock browser failure";

  @TempDir Path temp;

  private MarkdownWindow w;
  private CommandStates states;

  @BeforeEach
  void open() {
    w = new MarkdownWindow(temp);
    states = w.window.menuBar().states();
  }

  @AfterEach
  void close() {
    w.close();
  }

  private MarkdownToolItems tools() {
    return w.window.markdownSwitch().tools();
  }

  private MenuItem entry(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
    }
    return null;
  }

  private void select(MenuItem item) {
    item.setSelection(true);
    item.notifyListeners(SWT.Selection, new Event());
    w.flush();
  }

  private Menu renderers() {
    StatusField field = w.window.previewField();
    Event event = new Event();
    event.button = 1;
    field.notifyListeners(SWT.MouseUp, event);
    Menu popup = w.window.statusMenus().rendererPopup();
    assertNotNull(popup);
    popup.setVisible(false);
    return popup;
  }

  /** Reports whether the icon's lower right corner, where its down arrow sits, has opaque ink. */
  private static boolean arrowInked(ImageData data) {
    int side = data.width;
    for (int y = side * 9 / 16; y < side * 13 / 16; y++) {
      for (int x = side * 10 / 16; x < side; x++) {
        if (data.getAlpha(x, y) > 128) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  void theActionsButtonShowsForAMarkdownTabOnly() throws Exception {
    w.open("notes.txt", "plain\n", false);
    assertNull(tools().item());
    w.open("guide.md", "# Mock guide\n", true);
    ToolItem item = tools().item();
    assertNotNull(item);
    assertTrue(
        (item.getStyle() & SWT.PUSH) != 0, "a push item: win32 draws a drop-down arrow black");
    assertNotNull(item.getImage());
    assertTrue(arrowInked(item.getImage().getImageData()), "the icon carries its down arrow");
    assertEquals(UiText.MARKDOWN_ACTIONS.text(), item.getToolTipText());
    assertTrue(item.getData() instanceof ToolMenu);
    w.controller.documents().select(w.window.tabs().getItemCount() - 2);
    w.flush();
    assertNull(tools().item());
  }

  @Test
  void theMenuListsTheActionsInOrderWithTheirState() throws Exception {
    w.open("guide.md", "# Mock guide\n", true);
    Menu menu = tools().menu();
    MenuItem[] items = menu.getItems();
    assertEquals(MarkdownToolItems.COMMANDS.length, items.length);
    for (int i = 0; i < items.length; i++) {
      CommandId command = MarkdownToolItems.COMMANDS[i];
      if (command == null) {
        assertTrue((items[i].getStyle() & SWT.SEPARATOR) != 0, "separator " + i);
        continue;
      }
      assertEquals(command, items[i].getData());
      assertTrue(items[i].getText().startsWith(command.label()), items[i].getText());
      assertEquals(states.enabled(command), items[i].getEnabled(), command.name());
    }
    assertTrue((items[0].getStyle() & SWT.RADIO) != 0);
    assertTrue((items[1].getStyle() & SWT.RADIO) != 0);
    assertTrue((items[3].getStyle() & SWT.CHECK) != 0);
    assertTrue((items[4].getStyle() & SWT.CHECK) != 0);
    assertTrue((items[6].getStyle() & SWT.PUSH) != 0);
    assertTrue(items[0].getSelection());
    assertFalse(items[1].getSelection());
    assertTrue(items[3].getSelection());
    assertFalse(items[4].getSelection());
    // Radio and check items carry no icon: the GTK 3 alignment blank or none.
    assertSame(items[0].getImage(), items[3].getImage());
  }

  @Test
  void eachToggleRunsItsCommandAndTheMenuFollows() throws Exception {
    w.open("guide.md", "# Mock guide\n", true);
    select(entry(tools().menu(), CommandId.MARKDOWN_SYNC_SCROLL));
    assertFalse(w.options().markdownSyncScroll());
    assertFalse(entry(tools().menu(), CommandId.MARKDOWN_SYNC_SCROLL).getSelection());
    select(entry(tools().menu(), CommandId.MARKDOWN_OUTLINE));
    assertTrue(states.checked(CommandId.MARKDOWN_OUTLINE));
    assertTrue(entry(tools().menu(), CommandId.MARKDOWN_OUTLINE).getSelection());
    w.controller.execute(CommandId.MARKDOWN_OUTLINE);
    w.flush();
    assertFalse(entry(tools().menu(), CommandId.MARKDOWN_OUTLINE).getSelection());
  }

  @Test
  void theRendererItemsAndTheStatusFieldChooseTheRenderer() throws Exception {
    assumeTrue(!RunningPlatform.GTK4, "no Browser under GTK 4");
    w.open("guide.md", "# Mock guide\n", true);
    Menu menu = tools().menu();
    MenuItem basic = entry(menu, CommandId.PREVIEW_BASIC);
    MenuItem browser = entry(menu, CommandId.PREVIEW_BROWSER);
    assertTrue(browser.getEnabled());
    // An unchecked radio item's event runs nothing.
    basic.setSelection(false);
    basic.notifyListeners(SWT.Selection, new Event());
    select(browser);
    assertEquals(PreviewRenderer.BROWSER, w.options().previewRenderer());
    assertTrue(entry(tools().menu(), CommandId.PREVIEW_BROWSER).getSelection());
    select(entry(tools().menu(), CommandId.PREVIEW_BASIC));
    assertEquals(PreviewRenderer.BASIC, w.options().previewRenderer());

    w.controller.execute(CommandId.MARKDOWN_SPLIT);
    w.flush();
    assertEquals(UiText.STATUS_PREVIEW_TIP.text(), w.window.previewField().getToolTipText());
    Menu popup = renderers();
    assertEquals(2, popup.getItemCount());
    assertEquals(CommandId.PREVIEW_BASIC, popup.getItem(0).getData());
    assertTrue(popup.getItem(0).getSelection());
    assertFalse(popup.getItem(1).getSelection());
    assertTrue(popup.getItem(1).getEnabled());
    // Source first: the choice is the test, not a WebKit start.
    w.controller.execute(CommandId.MARKDOWN_SOURCE);
    select(popup.getItem(1));
    assertEquals(PreviewRenderer.BROWSER, w.options().previewRenderer());
    assertTrue(entry(tools().menu(), CommandId.PREVIEW_BROWSER).getSelection());
  }

  @Test
  void browserUnavailableDisablesTheMenuItemAndThePopupItemWithTheReason() throws Exception {
    BrowserPreview.failed(w.display, MOCK_REASON);
    String reason = w.window.previewBrowserUnavailable();
    w.openSplit("guide.md", "# Mock guide\n", "Mock guide");
    w.window.documentStateChanged();
    MenuItem item = entry(tools().menu(), CommandId.PREVIEW_BROWSER);
    assertFalse(item.getEnabled());
    assertFalse(item.getSelection());
    String text = !RunningPlatform.GTK4 ? item.getToolTipText() : item.getText();
    assertTrue(text.contains(reason), text);
    Menu popup = renderers();
    MenuItem browser = popup.getItem(1);
    assertFalse(browser.getEnabled());
    String shown = !RunningPlatform.GTK4 ? browser.getToolTipText() : browser.getText();
    assertTrue(shown.contains(reason), shown);
    assertTrue(popup.getItem(0).getSelection());
  }
}
