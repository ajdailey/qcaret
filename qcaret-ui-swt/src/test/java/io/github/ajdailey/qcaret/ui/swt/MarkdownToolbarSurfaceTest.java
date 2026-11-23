package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.PreviewRenderer;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Markdown toolbar buttons after {@code Source | Split | Preview} and the status bar's renderer
 * popup: present for a Markdown tab only, each showing and driving its menu item's state, and
 * Browser disabled with its reason where it cannot run.
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

  private ToolItem item(CommandId command) {
    return w.window.markdownSwitch().tools().item(command);
  }

  /** A click: SWT flips a check item's selection before its Selection event. */
  private void click(ToolItem item) {
    if ((item.getStyle() & SWT.CHECK) != 0) {
      item.setSelection(!item.getSelection());
    }
    item.notifyListeners(SWT.Selection, new Event());
    w.flush();
  }

  private void select(MenuItem item) {
    item.setSelection(true);
    item.notifyListeners(SWT.Selection, new Event());
    w.flush();
  }

  private Menu renderers() {
    Label field = w.window.previewField();
    Event event = new Event();
    event.button = 1;
    field.notifyListeners(SWT.MouseUp, event);
    Menu popup = w.window.statusMenus().rendererPopup();
    assertNotNull(popup);
    popup.setVisible(false);
    return popup;
  }

  @Test
  void theButtonsShowForAMarkdownTabOnly() throws Exception {
    w.open("notes.txt", "plain\n", false);
    for (CommandId command : MarkdownToolItems.COMMANDS) {
      assertNull(item(command), command.name());
    }
    w.open("guide.md", "# Mock guide\n", true);
    for (CommandId command : MarkdownToolItems.COMMANDS) {
      ToolItem item = item(command);
      assertNotNull(item, command.name());
      assertNotNull(item.getImage(), command.name() + " icon");
      assertEquals(states.enabled(command), item.getEnabled(), command.name());
    }
    assertTrue(item(CommandId.EXPORT_HTML).getEnabled());
    assertTrue(item(CommandId.PRINT_MARKDOWN).getEnabled());
    assertTrue(item(CommandId.MARKDOWN_SYNC_SCROLL).getSelection());
    assertFalse(item(CommandId.MARKDOWN_OUTLINE).getSelection());
    assertFalse(item(CommandId.PREVIEW_BROWSER).getSelection());
    assertTrue(item(CommandId.EXPORT_HTML).getToolTipText().startsWith("Export as HTML"));
    w.controller.documents().select(0);
    w.flush();
    assertNull(item(CommandId.EXPORT_HTML));
  }

  @Test
  void theTogglesFollowAndDriveTheirMenuItems() throws Exception {
    w.open("guide.md", "# Mock guide\n", true);
    w.controller.execute(CommandId.MARKDOWN_SYNC_SCROLL);
    w.flush();
    assertFalse(w.options().markdownSyncScroll());
    assertFalse(item(CommandId.MARKDOWN_SYNC_SCROLL).getSelection());
    click(item(CommandId.MARKDOWN_SYNC_SCROLL));
    assertTrue(w.options().markdownSyncScroll());
    assertTrue(item(CommandId.MARKDOWN_SYNC_SCROLL).getSelection());

    click(item(CommandId.MARKDOWN_OUTLINE));
    assertTrue(states.checked(CommandId.MARKDOWN_OUTLINE));
    assertTrue(item(CommandId.MARKDOWN_OUTLINE).getSelection());
    w.controller.execute(CommandId.MARKDOWN_OUTLINE);
    w.flush();
    assertFalse(item(CommandId.MARKDOWN_OUTLINE).getSelection());
  }

  @Test
  void theRendererToggleAndTheStatusFieldChooseTheRenderer() throws Exception {
    assumeTrue(PlatformTraits.PLATFORM.opensBrowser(), "no Browser under GTK 4");
    w.open("guide.md", "# Mock guide\n", true);
    ToolItem toggle = item(CommandId.PREVIEW_BROWSER);
    assertTrue(toggle.getEnabled());
    click(toggle);
    assertEquals(PreviewRenderer.BROWSER, w.options().previewRenderer());
    assertTrue(toggle.getSelection());
    w.controller.execute(CommandId.PREVIEW_BASIC);
    w.flush();
    assertFalse(toggle.getSelection());

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
    assertTrue(toggle.getSelection());
  }

  @Test
  void theRendererToggleToolTipNamesTheRendererShownAndTheClick() throws Exception {
    assumeTrue(PlatformTraits.PLATFORM.opensBrowser(), "no Browser under GTK 4");
    w.open("guide.md", "# Mock guide\n", true);
    ToolItem toggle = item(CommandId.PREVIEW_BROWSER);
    assertTrue(
        toggle.getToolTipText().startsWith(UiText.PREVIEW_TOGGLE_BASIC.text()),
        toggle.getToolTipText());
    w.controller.execute(CommandId.PREVIEW_BROWSER);
    w.flush();
    assertTrue(
        toggle.getToolTipText().startsWith(UiText.PREVIEW_TOGGLE_BROWSER.text()),
        toggle.getToolTipText());
    click(toggle);
    assertEquals(PreviewRenderer.BASIC, w.options().previewRenderer());
    assertTrue(
        toggle.getToolTipText().startsWith(UiText.PREVIEW_TOGGLE_BASIC.text()),
        toggle.getToolTipText());
  }

  @Test
  void browserUnavailableDisablesTheToggleAndThePopupItemWithTheReason() throws Exception {
    BrowserPreview.failed(w.display, MOCK_REASON);
    String reason = w.window.previewBrowserUnavailable();
    w.openSplit("guide.md", "# Mock guide\n", "Mock guide");
    w.window.documentStateChanged();
    ToolItem toggle = item(CommandId.PREVIEW_BROWSER);
    assertFalse(toggle.getEnabled());
    assertFalse(toggle.getSelection());
    assertTrue(toggle.getToolTipText().contains(reason), toggle.getToolTipText());
    Menu popup = renderers();
    MenuItem browser = popup.getItem(1);
    assertFalse(browser.getEnabled());
    String shown =
        PlatformTraits.PLATFORM.showsMenuToolTips() ? browser.getToolTipText() : browser.getText();
    assertTrue(shown.contains(reason), shown);
    assertTrue(popup.getItem(0).getSelection());
  }
}
