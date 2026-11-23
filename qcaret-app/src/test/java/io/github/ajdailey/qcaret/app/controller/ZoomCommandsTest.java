package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The zoom commands act on the pane {@code MainView.previewFocused} names: the editor font or the
 * Markdown preview's {@code markdown.previewZoom}, each leaving the other unchanged; Reset Zoom,
 * the limits, the enabled state and a wheel turn aimed at a pane.
 */
class ZoomCommandsTest {
  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private Options options() {
    return harness.application.preferences().options();
  }

  @Test
  void theEditorZoomLeavesThePreviewUnchanged() {
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(11, options().fontZoom());
    assertEquals(100, options().previewZoom());
    harness.application.execute(CommandId.ZOOM_RESET);
    assertEquals(0, options().fontZoom());
    assertEquals(100, options().previewZoom());
  }

  /**
   * A step back to the size drawn without a zoom clears the zoom, at the platform size as at a
   * Preferences size. Regression: with {@code editor.fontSize} 0, zooming in and out stored the
   * platform size, so the zoom stayed set.
   */
  @Test
  void aStepBackToTheDrawnSizeClearsTheZoom() {
    assertEquals(0, options().fontSize());
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(11, options().fontZoom());
    harness.application.execute(CommandId.ZOOM_OUT);
    assertEquals(0, options().fontZoom());
    harness.application.execute(CommandId.ZOOM_OUT);
    assertEquals(9, options().fontZoom());
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(0, options().fontZoom());

    Preferences current = harness.application.preferences();
    harness.application.settings().set(current.withOptions(current.options().withFontSize(14)));
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(15, options().fontZoom());
    harness.application.execute(CommandId.ZOOM_OUT);
    assertEquals(0, options().fontZoom());
    harness.application.execute(CommandId.ZOOM_OUT);
    assertEquals(13, options().fontZoom());
  }

  @Test
  void thePreviewZoomLeavesTheEditorUnchanged() {
    harness.view.previewFocused = true;
    harness.application.execute(CommandId.ZOOM_IN);
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(120, options().previewZoom());
    assertEquals(0, options().fontZoom());
    harness.application.execute(CommandId.ZOOM_OUT);
    assertEquals(110, options().previewZoom());
    harness.application.execute(CommandId.ZOOM_RESET);
    assertEquals(100, options().previewZoom());
    assertEquals(0, options().fontZoom());
  }

  @Test
  void eachPaneKeepsItsOwnZoomAndLimits() {
    Preferences current = harness.application.preferences();
    harness
        .application
        .settings()
        .set(
            current.withOptions(
                current.options().withPreviewZoom(Options.MAX_PREVIEW_ZOOM).withFontZoom(12)));
    harness.view.previewFocused = true;
    assertFalse(states.enabled(CommandId.ZOOM_IN));
    assertTrue(states.enabled(CommandId.ZOOM_OUT));
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(Options.MAX_PREVIEW_ZOOM, options().previewZoom());
    harness.view.previewFocused = false;
    assertTrue(states.enabled(CommandId.ZOOM_IN));
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(13, options().fontZoom());
    assertEquals(Options.MAX_PREVIEW_ZOOM, options().previewZoom());
  }

  @Test
  void aWheelTurnZoomsThePaneUnderIt() {
    harness.application.wheelZoom(true, 1);
    assertEquals(110, options().previewZoom());
    assertEquals(0, options().fontZoom());
    harness.view.previewFocused = true;
    harness.application.wheelZoom(false, -1);
    assertEquals(9, options().fontZoom());
    assertEquals(110, options().previewZoom());
  }

  @Test
  void aPreviewTakesTheZoomWhenShownAndWhenItChanges() throws Exception {
    final GrammarLibrary library = harness.application.grammars();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
    Path file = Files.writeString(temp.resolve("notes.md"), "# Mock\n", StandardCharsets.UTF_8);
    harness.view.previewFocused = true;
    harness.application.execute(CommandId.ZOOM_IN);
    harness.open(file);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().activeMarkdown();
          }
        },
        "markdown");
    harness.application.execute(CommandId.MARKDOWN_SPLIT);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().markdownMode() == MarkdownViewMode.SPLIT;
          }
        },
        "split");
    FakeMarkdownPane pane = harness.view.selected.markdown;
    assertEquals(110, pane.zooms.get(pane.zooms.size() - 1));
    harness.application.execute(CommandId.ZOOM_OUT);
    assertEquals(100, pane.zooms.get(pane.zooms.size() - 1));
  }
}
