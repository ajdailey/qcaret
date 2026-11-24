package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.BlockSpacing;
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
import io.github.ajdailey.qcaret.ui.swt.ScaleSlack;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownPane} block spacing, the code block's text indent, a Find match across a wrapped
 * cell's break, and a table far below the view that waits for its layout until the view comes near.
 */
@Tag("swt")
class MarkdownPaneBlocksTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String LONG =
      "a mock note of several words that runs on for a while and then some more words";

  private static final String TABLE =
      String.join(
          "\n",
          "| Name | Note |",
          "|:--|:--|",
          "| first | " + LONG + " " + LONG + " |",
          "| second | short |",
          "");

  /** A table 200 paragraphs below the top, under a heading. */
  private static final String FAR =
      "Intro.\n" + "\nFiller.\n".repeat(200) + "\n## Far\n\n" + TABLE + "\nFiller.\n".repeat(40);

  private Display display;
  private Shell shell;
  private MarkdownPane pane;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(new FillLayout());
    shell.setSize(700, 300);
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
                // No clicks.
              }

              @Override
              public void linkFollowed(String location) {
                // No links followed.
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

  private void idle() {
    flush();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !pane.tables().busy();
          }
        },
        "no turn queued");
    flush();
  }

  private void awaitLaidOut() {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.tables().laidOut(0) && !pane.tables().busy();
          }
        },
        "table laid out");
    flush();
  }

  private RenderedMarkdown show(String markdown) {
    RenderedMarkdown r = new MarkdownRenderer().render(markdown, null, new CancellationToken());
    pane.showRendered(
        new MarkdownPreviewContent(
            r, List.of(), MarkdownSpans.of(r, List.of()), new ArrayList<MarkdownImageRequest>()));
    idle();
    return r;
  }

  private int height(int line) {
    StyledText text = pane.text();
    return text.getLinePixel(line + 1) - text.getLinePixel(line);
  }

  @Test
  void separatorsAddSpaceAroundHeadingsAndCodeBlocks() {
    RenderedMarkdown r =
        show("Intro\n\n## Two\n\n```\ncode\n```\n\nEnd\n" + "\nFiller.\n".repeat(20));
    int two = r.headings().get(0).line();
    int code = r.codeBlocks().get(0).firstLine();
    int body = height(0);
    int slack = ScaleSlack.of(pane.text());
    assertEquals(body + BlockSpacing.HEADING_ABOVE, height(two - 1), slack, "above the heading");
    assertEquals(
        body + BlockSpacing.RULE_GAP + BlockSpacing.CODE_PADDING,
        height(two + 1),
        slack,
        "rule gap and code padding");
    assertEquals(body + BlockSpacing.CODE_PADDING, height(code + 1), slack, "below the code");
    StyledText text = pane.text();
    int at = pane.map().display(r.lineStart(code));
    assertEquals(text.getLeftMargin() + MarkdownStyler.CODE_INDENT, text.getLocationAtOffset(at).x);
  }

  @Test
  void aWrappedCodeLineContinuesUnderItsOwnText() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 320, 300);
    flush();
    RenderedMarkdown r =
        show("```\n    mock(alpha, beta, gamma, delta, epsilon, zeta, eta, theta, iota);\n```\n");
    StyledText text = pane.text();
    int line = pane.map().displayLine(r.codeBlocks().get(0).firstLine());
    int start = text.getOffsetAtLine(line);
    int y = text.getLocationAtOffset(start).y;
    int wrapped = -1;
    for (int i = start; i < start + text.getLine(line).length(); i++) {
      if (text.getLocationAtOffset(i).y > y) {
        wrapped = i;
        break;
      }
    }
    assertTrue(wrapped > 0, "the code line wraps");
    int own = text.getLocationAtOffset(start + 4).x;
    assertEquals(
        text.getLeftMargin() + MarkdownStyler.CODE_INDENT, text.getLocationAtOffset(start).x);
    assertEquals(own, text.getLocationAtOffset(wrapped).x, "continuation under the line's text");
  }

  /** Find across a wrapped cell's break selected the other cells' segments between its ends. */
  @Test
  void aMatchAcrossAWrappedCellBreakSelectsItsFirstLine() {
    RenderedMarkdown r = show("Intro.\n\n" + TABLE);
    awaitLaidOut();
    String cell = LONG + " " + LONG;
    int start = r.text().indexOf(cell);
    pane.selectRendered(start, start + cell.length());
    StyledText text = pane.text();
    String selected = text.getSelectionText();
    assertTrue(!selected.isEmpty() && cell.startsWith(selected), selected);
    assertEquals(
        text.getLineAtOffset(text.getSelection().x), text.getLineAtOffset(text.getSelection().y));
  }

  @Test
  void aFarTableWaitsUntilOutlineGoToReachesIt() {
    RenderedMarkdown r = show(FAR);
    assertTrue(pane.tables().waiting(0), "far table waits");
    assertFalse(pane.tables().laidOut(0));
    int heading = r.headings().get(0).line();
    pane.scrollToLine(heading);
    flush();
    awaitLaidOut();
    assertEquals(pane.map().displayLine(heading), pane.text().getTopIndex());
    assertTrue(pane.map().displayLineCount(r.tables().get(0).firstLine() + 1) > 1, "wrapped");
  }

  @Test
  void findInAWaitingTableSelectsTheMatchOnceLaidOut() {
    RenderedMarkdown r = show(FAR);
    assertTrue(pane.tables().waiting(0));
    int at = r.text().indexOf("second");
    pane.selectRendered(at, at + 6);
    awaitLaidOut();
    StyledText text = pane.text();
    assertEquals("second", text.getSelectionText());
    int line = text.getLineAtOffset(text.getSelection().x);
    assertTrue(
        line >= text.getTopIndex() && text.getLinePixel(line) < text.getClientArea().height,
        "the match is in view");
  }

  @Test
  void aResizeKeepsAFarTableWaitingAndItsLayoutUsesTheNewWidth() {
    RenderedMarkdown r = show(FAR);
    final int before = pane.text().getClientArea().width;
    shell.setSize(450, 300);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.text().getClientArea().width < before;
          }
        },
        "resized");
    idle();
    assertTrue(pane.tables().waiting(0), "still waits");
    pane.scrollToLine(r.headings().get(0).line());
    flush();
    awaitLaidOut();
    StyledText text = pane.text();
    int[] edges = pane.tables().edges(0);
    assertTrue(
        edges[edges.length - 1]
            <= text.getClientArea().width - text.getLeftMargin() - text.getRightMargin());
  }
}
