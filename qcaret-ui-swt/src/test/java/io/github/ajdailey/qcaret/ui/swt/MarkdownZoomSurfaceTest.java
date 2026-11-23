package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zoom In, Zoom Out and Reset Zoom act on the pane that had the focus last, the editor or the Basic
 * preview, and leave the other's size; a wheel turn with Ctrl zooms the pane under it.
 */
@Tag("swt")
class MarkdownZoomSurfaceTest {
  @TempDir Path temp;

  private MarkdownWindow w;
  private int editorBase;
  private int previewBase;

  @BeforeEach
  void open() throws Exception {
    w = new MarkdownWindow(temp);
    w.openSplit("guide.md", "# Mock guide\n\nBody text.\n", "Mock guide");
    w.window.shell().forceActive();
    w.flush();
    editorBase = height(editor());
    previewBase = height(preview());
  }

  @AfterEach
  void close() {
    w.close();
  }

  private StyledText editor() {
    return w.window.activeEditor().text();
  }

  private StyledText preview() {
    return w.window.activePreview().text();
  }

  private static int height(StyledText text) {
    return text.getFont().getFontData()[0].getHeight();
  }

  private void focus(final StyledText text, final boolean preview) {
    text.setFocus();
    w.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return w.window.previewFocused() == preview;
          }
        },
        "preview focused " + preview);
  }

  private void awaitHeight(final StyledText text, final int points) {
    w.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return height(text) == points;
          }
        },
        "font " + height(text) + ", expected " + points);
  }

  private static void wheel(StyledText text, int count) {
    Event event = new Event();
    event.count = count;
    event.stateMask = SWT.MOD1;
    event.x = 5;
    event.y = 5;
    text.notifyListeners(SWT.MouseVerticalWheel, event);
  }

  @Test
  void eachZoomActsOnTheFocusedPaneOnly() {
    focus(preview(), true);
    w.controller.execute(CommandId.ZOOM_IN);
    w.controller.execute(CommandId.ZOOM_IN);
    assertEquals(120, w.options().previewZoom());
    awaitHeight(preview(), Math.round(previewBase * 1.2f));
    assertEquals(0, w.controller.fontSize());
    assertEquals(editorBase, height(editor()));

    focus(editor(), false);
    w.controller.execute(CommandId.ZOOM_IN);
    assertEquals(editorBase + 1, w.controller.fontSize());
    awaitHeight(editor(), editorBase + 1);
    assertEquals(120, w.options().previewZoom());
    assertEquals(Math.round(previewBase * 1.2f), height(preview()));

    w.controller.execute(CommandId.ZOOM_RESET);
    awaitHeight(editor(), editorBase);
    assertEquals(120, w.options().previewZoom());
    focus(preview(), true);
    w.controller.execute(CommandId.ZOOM_RESET);
    assertEquals(100, w.options().previewZoom());
    awaitHeight(preview(), previewBase);
    assertEquals(0, w.controller.fontSize());
  }

  @Test
  void theFindBarKeepsThePreviewAsTheZoomTarget() {
    focus(preview(), true);
    w.controller.execute(CommandId.FIND);
    w.flush();
    assertTrue(w.window.previewFocused());
    w.controller.execute(CommandId.ZOOM_OUT);
    assertEquals(90, w.options().previewZoom());
    assertEquals(0, w.controller.fontSize());
  }

  @Test
  void aWheelTurnWithCtrlZoomsThePaneUnderIt() {
    focus(editor(), false);
    wheel(preview(), 3);
    assertEquals(110, w.options().previewZoom());
    assertEquals(0, w.controller.fontSize());
    awaitHeight(preview(), Math.round(previewBase * 1.1f));
    focus(preview(), true);
    wheel(editor(), -3);
    assertEquals(editorBase - 1, w.controller.fontSize());
    assertEquals(110, w.options().previewZoom());
    assertFalse(w.window.activePreview().text().isDisposed());
  }

  @Test
  void aPreviewOpenedLaterTakesTheZoom() throws Exception {
    focus(preview(), true);
    w.controller.execute(CommandId.ZOOM_IN);
    w.openSplit("other.md", "# Other mock\n", "Other mock");
    assertEquals(Math.round(previewBase * 1.1f), height(preview()));
  }
}
