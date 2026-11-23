package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownPane#setZoom}: the fonts scale, the replaced fonts are disposed, the tables are
 * measured and laid out again in the new fonts, and the top line stays. Images keep their size.
 */
@Tag("swt")
class MarkdownPaneZoomTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String CELL = "a mock cell of several words that wraps in its column";

  /** Filler, a wrapping table, more filler and a second table. */
  private static final String DOCUMENT =
      "Intro\n\n"
          + "Filler line.\n\n".repeat(30)
          + "| Name | Note |\n|--|--|\n"
          + ("| mock | " + CELL + " " + CELL + " |\n").repeat(8)
          + "\n"
          + "Filler after.\n\n".repeat(30)
          + "| A | B |\n|--|--|\n| one | two |\n";

  private Display display;
  private MarkdownPane pane;

  @BeforeEach
  void open() {
    display = new Display();
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    shell.setSize(700, 400);
    pane =
        new MarkdownPane(
            shell,
            new MarkdownPreviewInput() {
              @Override
              public void attach(MarkdownPreviewView view) {
                // Not attached.
              }

              @Override
              public void renderRequested() {
                // No notice.
              }

              @Override
              public void previewScrolled(int line) {
                // Not followed.
              }

              @Override
              public void clicked(int offset) {
                // No links.
              }

              @Override
              public void linkFollowed(String location) {
                // No links.
              }

              @Override
              public void browserFailed() {
                // Basic only.
              }
            },
            Palette.LIGHT,
            new SwtUiDispatcher(display),
            PlatformTraits.PLATFORM.monospaceFamily());
    shell.open();
  }

  @AfterEach
  void close() {
    if (!display.isDisposed()) {
      display.dispose();
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Paint.
    }
  }

  private void awaitTable(final int table) {
    flush();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.tables().laidOut(table) && !pane.tables().busy();
          }
        },
        "table " + table + " laid out");
    flush();
  }

  private RenderedMarkdown show(String markdown) {
    RenderedMarkdown r = new MarkdownRenderer().render(markdown, null, new CancellationToken());
    pane.showRendered(
        new MarkdownPreviewContent(
            r, List.of(), MarkdownSpans.of(r, List.of()), new ArrayList<MarkdownImageRequest>()));
    return r;
  }

  private int bodyHeight() {
    return pane.text().getFont().getFontData()[0].getHeight();
  }

  @Test
  void aZoomScalesTheFontsAndDisposesTheReplacedOnes() {
    show("# Heading\n\nBody text.\n");
    flush();
    int base = bodyHeight();
    Font heading = headingFont();
    pane.setZoom(200);
    flush();
    assertEquals(Math.round(base * 2f), bodyHeight());
    assertTrue(heading.isDisposed(), "the old heading font is disposed");
    Font zoomed = headingFont();
    assertEquals(
        Math.round(base * 1.75f * 2f), zoomed.getFontData()[0].getHeight(), "level 1 heading");
    pane.setZoom(100);
    flush();
    assertEquals(base, bodyHeight());
    assertTrue(zoomed.isDisposed());
  }

  @Test
  void aZoomLaysOutTheTablesAgainAndKeepsTheTopLine() {
    RenderedMarkdown r = show(DOCUMENT);
    final int table = r.tables().get(0).firstLine();
    pane.scrollToLine(table - 2);
    awaitTable(0);
    final int top = pane.map().renderedLine(pane.text().getTopIndex());
    assertEquals(table - 2, top);
    int[] before = pane.tables().edges(0).clone();
    final int rowLines = pane.map().displayLineCount(table + 2);
    pane.setZoom(200);
    assertFalse(pane.tables().laidOut(0), "the table is laid out again");
    awaitTable(0);
    assertFalse(Arrays.equals(before, pane.tables().edges(0)), "columns measured in new fonts");
    assertTrue(pane.map().displayLineCount(table + 2) > rowLines, "larger text wraps more");
    assertEquals(top, pane.map().renderedLine(pane.text().getTopIndex()));
    assertEquals(0, pane.text().getLinePixel(pane.text().getTopIndex()));
  }

  @Test
  void aTableAwayFromTheViewWaitsAfterAZoom() {
    RenderedMarkdown r = show(DOCUMENT);
    pane.scrollToLine(r.tables().get(0).firstLine());
    awaitTable(0);
    pane.setZoom(80);
    awaitTable(0);
    assertTrue(pane.tables().waiting(1), "the far table waits for the view");
  }

  private Font headingFont() {
    return pane.styler().style(0, 0, pane.text().getLine(0).length()).styles[0].font;
  }
}
