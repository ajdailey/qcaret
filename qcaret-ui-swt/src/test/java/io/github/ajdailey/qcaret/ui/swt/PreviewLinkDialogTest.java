package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dialog a Basic preview link to a web address opens before the address does: the full address,
 * Open and Cancel with Cancel the default; Open runs the launch, Cancel nothing.
 */
@Tag("swt")
class PreviewLinkDialogTest {
  private static final String URL = "https://example.invalid/mock/a-long-path?query=mock#part";

  @TempDir Path temp;

  private MarkdownWindow w;

  @BeforeEach
  void open() {
    w = new MarkdownWindow(temp);
  }

  @AfterEach
  void close() {
    w.close();
  }

  @Test
  void openRunsTheLaunchAndCancelDoesNot() {
    final AtomicInteger opened = new AtomicInteger();
    Runnable open =
        new Runnable() {
          @Override
          public void run() {
            opened.incrementAndGet();
          }
        };
    w.window.askOpenUrl(URL, open);
    OpenLinkDialog dialog = w.window.openLinkDialog();
    assertEquals(UiText.OPEN_LINK_TITLE.text(), dialog.shell().getText());
    assertEquals(URL, dialog.address().getText());
    assertSame(dialog.cancel(), dialog.shell().getDefaultButton());
    dialog.cancel().notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.shell().isDisposed());
    assertEquals(0, opened.get());

    w.window.askOpenUrl(URL, open);
    dialog = w.window.openLinkDialog();
    dialog.openButton().notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.shell().isDisposed());
    assertEquals(1, opened.get());
  }

  @Test
  void aClickOnAWebLinkAsksAndALocalLinkDoesNot() throws Exception {
    w.openSplit("guide.md", "# Mock guide\n\n[web](" + URL + ") and [local](#mock-guide)\n", "web");
    String text = w.window.activePreview().text().getText();
    w.controller.documents().markdownPreview().clicked(text.indexOf("local") + 1);
    w.flush();
    assertNull(w.window.openLinkDialog());
    w.controller.documents().markdownPreview().clicked(text.indexOf("web") + 1);
    OpenLinkDialog dialog = w.window.openLinkDialog();
    assertNotNull(dialog);
    assertEquals(URL, dialog.address().getText());
    dialog.cancel().notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.shell().isDisposed());
  }
}
