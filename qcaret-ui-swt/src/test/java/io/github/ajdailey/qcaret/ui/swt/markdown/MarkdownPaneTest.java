package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.CodeBlock;
import io.github.ajdailey.qcaret.core.markdown.MarkdownImage;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MarkdownPane} in a shell of its own over a fixture holding every element: the line styles
 * of each, a theme switch, link clicks, table measuring in slices, and images decoded off the UI
 * thread, stale results dropped and every image disposed.
 */
@Tag("swt")
class MarkdownPaneTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  /** Every element the Basic renderer maps; file names are relative to the temp folder. */
  static final String FIXTURE =
      String.join(
          "\n",
          "---",
          "title: Mock",
          "---",
          "",
          "# Heading One",
          "",
          "## Heading Two",
          "",
          "Text with **strong**, *emphasis*, ~~strike~~, `code`, [a link](#heading-two),",
          "<kbd>Ctrl</kbd>, H<sub>2</sub>O and x<sup>2</sup>. A note[^n].",
          "",
          "- bullet",
          "- [x] done task",
          "- [ ] open task",
          "",
          "1. first",
          "2. second",
          "",
          "> quote line",
          ">",
          "> > nested quote",
          "",
          "> [!warning]",
          "> Mock warning text.",
          "",
          "---",
          "",
          "```java",
          "// mock comment",
          "int x = 1;",
          "```",
          "",
          "| Left | Center | Right |",
          "|:-----|:------:|------:|",
          "| a | bb | ccc |",
          "| long cell text | x | 1 |",
          "",
          "![shot](shot.png){width=64} ![logo](logo.svg)",
          "",
          "![remote](https://example.invalid/r.png)",
          "",
          "[[_TOC_]]",
          "",
          ">>>",
          "A multi-line quote.",
          ">>>",
          "",
          "[^n]: The note.",
          "");

  @TempDir Path temp;

  private Display display;
  private Shell shell;
  private MarkdownPane pane;
  private final List<Integer> clicks = new ArrayList<Integer>();
  private final List<Integer> scrolled = new ArrayList<Integer>();

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(new FillLayout());
    shell.setSize(700, 500);
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
                scrolled.add(line);
              }

              @Override
              public void clicked(int offset) {
                clicks.add(offset);
              }
            },
            Palette.LIGHT,
            new SwtUiDispatcher(display),
            "Monospace");
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

  /** Renders a text with its drawable images and one keyword token per code block's first char. */
  MarkdownPreviewContent content(String markdown) {
    RenderedMarkdown r = new MarkdownRenderer().render(markdown, temp, new CancellationToken());
    List<int[]> tokens = new ArrayList<int[]>();
    for (CodeBlock block : r.codeBlocks()) {
      int start = r.lineStart(block.firstLine());
      tokens.add(new int[] {start, start + 2, TokenRole.COMMENT.ordinal()});
    }
    List<MarkdownImageRequest> images = new ArrayList<MarkdownImageRequest>();
    for (MarkdownImage image : r.images()) {
      if (image.drawable()) {
        images.add(
            new MarkdownImageRequest(image.start(), image.target().path(), image.alt(), 64, 0, 0));
      }
    }
    return new MarkdownPreviewContent(r, tokens, MarkdownSpans.of(r, tokens), images);
  }

  private MarkdownStyler.LineStyle line(int line) {
    StyledText text = pane.text();
    int start = text.getOffsetAtLine(line);
    return pane.styler().style(line, start, start + text.getLine(line).length());
  }

  private StyleRange at(MarkdownPreviewContent c, String word) {
    int offset = c.rendered().text().indexOf(word);
    assertTrue(offset >= 0, word);
    int line = pane.text().getLineAtOffset(offset);
    for (StyleRange r : line(line).styles) {
      if (r.start <= offset && offset < r.start + r.length) {
        return r;
      }
    }
    return null;
  }

  private int lineOf(MarkdownPreviewContent c, String word) {
    return pane.text().getLineAtOffset(c.rendered().text().indexOf(word));
  }

  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
  }

  private boolean bold(StyleRange r) {
    return (r.font.getFontData()[0].getStyle() & SWT.BOLD) != 0;
  }

  @Test
  void eachElementMapsOntoLineStyles() {
    MarkdownPreviewContent c = content(FIXTURE);
    pane.showRendered(c);
    flush();
    Palette p = Palette.LIGHT;
    int body = pane.text().getFont().getFontData()[0].getHeight();
    StyleRange h1 = at(c, "Heading One");
    assertTrue(bold(h1));
    assertTrue(h1.font.getFontData()[0].getHeight() > body);
    assertEquals(rgb(p.style(TokenRole.HEADING).foreground()), h1.foreground.getRGB());
    assertTrue(bold(at(c, "strong")));
    assertTrue((at(c, "emphasis").font.getFontData()[0].getStyle() & SWT.ITALIC) != 0);
    assertTrue(at(c, "strike").strikeout);
    StyleRange code = at(c, "code");
    assertEquals(rgb(p.gutter()), code.background.getRGB());
    StyleRange link = at(c, "a link");
    assertTrue(link.underline);
    assertEquals(rgb(p.style(TokenRole.LINK).foreground()), link.foreground.getRGB());
    assertEquals(SWT.BORDER_SOLID, at(c, "Ctrl").borderStyle);
    assertTrue(at(c, "2O").rise < 0);
    int sup = c.rendered().text().indexOf("x2") + 1;
    StyleRange superscript = null;
    for (StyleRange r : line(pane.text().getLineAtOffset(sup)).styles) {
      if (r.start == sup) {
        superscript = r;
      }
    }
    assertNotNull(superscript);
    assertTrue(superscript.rise > 0);
    assertEquals(ST.BULLET_DOT, line(lineOf(c, "bullet")).bullet.type & ST.BULLET_DOT);
    assertEquals("☑", line(lineOf(c, "done task")).bullet.text);
    assertEquals("☐", line(lineOf(c, "open task")).bullet.text);
    assertEquals("2.", line(lineOf(c, "second")).bullet.text);
    MarkdownStyler.LineStyle nested = line(lineOf(c, "nested quote"));
    assertEquals(2 * MarkdownStyler.QUOTE_INDENT, nested.indent);
    int comment = lineOf(c, "mock comment");
    assertNotNull(pane.styler().background(comment));
    StyleRange token = line(comment).styles[0];
    assertEquals(rgb(p.style(TokenRole.COMMENT).foreground()), token.foreground.getRGB());
    assertEquals("Monospace", token.font.getFontData()[0].getName());
    assertNull(pane.styler().background(lineOf(c, "Text with")));
    assertNotNull(pane.styler().background(lineOf(c, "title: Mock")));
    StyleRange header = at(c, "Left");
    assertTrue(bold(header));
    int image = c.images().get(0).offset();
    StyleRange glyph = null;
    for (StyleRange r : line(pane.text().getLineAtOffset(image)).styles) {
      if (r.start == image) {
        glyph = r;
      }
    }
    assertNotNull(glyph);
    assertEquals(64, glyph.metrics.width);
    assertTrue(c.rendered().text().contains("https://example.invalid/r.png"));
    assertNotNull(pane.styler().alertAt(lineOf(c, "Mock warning text")));
  }

  @Test
  void aThemeSwitchRecolorsInPlace() {
    MarkdownPreviewContent c = content(FIXTURE);
    pane.showRendered(c);
    flush();
    RGB light = at(c, "a link").foreground.getRGB();
    pane.setPalette(Palette.DARK);
    flush();
    RGB dark = at(c, "a link").foreground.getRGB();
    assertNotEquals(light, dark);
    assertEquals(rgb(Palette.DARK.style(TokenRole.LINK).foreground()), dark);
    assertEquals(rgb(Palette.DARK.background()), pane.text().getBackground().getRGB());
  }

  @Test
  void clicksForwardTheirOffset() {
    MarkdownPreviewContent c = content("[a link](#x) and text\n");
    pane.showRendered(c);
    flush();
    Point at = pane.text().getLocationAtOffset(2);
    Event up = new Event();
    up.button = 1;
    up.x = at.x + 1;
    up.y = at.y + 2;
    pane.text().notifyListeners(SWT.MouseUp, up);
    assertEquals(1, clicks.size());
    assertEquals(2, (int) clicks.get(0));
    pane.text().setSelection(0, 3);
    pane.text().notifyListeners(SWT.MouseUp, up);
    assertEquals(1, clicks.size());
  }

  @Test
  void aLargeTableMeasuresInSlicesInViewFirst() {
    StringBuilder text = new StringBuilder("Intro\n\n| a | b | c |\n|---|---|---|\n");
    for (int i = 0; i < 1000; i++) {
      text.append("| cell ").append(i).append(" | x | y |\n");
    }
    text.append("\n| p | q |\n|---|---|\n| r | s |\n");
    MarkdownPreviewContent c = content(text.toString());
    pane.showRendered(c);
    assertNull(pane.tables().measured(0));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.tables().measured(1) != null;
          }
        },
        "tables measured");
    assertTrue(pane.tables().turns() > 3, "turns " + pane.tables().turns());
    assertNotNull(pane.tables().measured(0));
    int row = c.rendered().tables().get(0).firstLine() + 5;
    MarkdownStyler.LineStyle event = line(row);
    assertNotNull(event.tabStops);
    assertEquals(2, event.tabStops.length);
    assertTrue(c.rendered().text().indexOf("cell 3") > 0);
    assertTrue(pane.text().getLine(row).contains(" "));
    pane.text().setSelection(c.rendered().lineStart(row), c.rendered().lineStart(row) + 6);
    pane.copy();
  }

  private static byte[] png(int width, int height) {
    ImageData data = new ImageData(width, height, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    loader.save(out, SWT.IMAGE_PNG);
    return out.toByteArray();
  }

  @Test
  void imagesDecodeOffTheUiThreadAndAreDisposedWithTheirContent() throws Exception {
    MarkdownPreviewContent c = content(FIXTURE);
    pane.showRendered(c);
    final MarkdownImageDecoder decoder = pane.imageDecoder();
    final MarkdownImageRequest request = c.images().get(0);
    final byte[] bytes = png(200, 100);
    final AtomicReference<DecodedImage> decoded = new AtomicReference<DecodedImage>();
    Thread worker =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                decoded.set(
                    decoder.decode(
                        bytes,
                        request,
                        new BooleanSupplier() {
                          @Override
                          public boolean getAsBoolean() {
                            return false;
                          }
                        }));
              }
            },
            "mock-worker");
    worker.start();
    worker.join(BOUND.toMillis());
    assertEquals("mock-worker", pane.decoder().thread());
    assertEquals(64, decoded.get().width());
    assertEquals(32, decoded.get().height());
    pane.showImage(request, decoded.get());
    assertEquals(1, pane.images().live());
    pane.showImage(c.images().get(1), null);
    assertTrue(pane.images().failed(c.images().get(1).offset()));
    flush();
    MarkdownPreviewContent next = content(FIXTURE + "\nmore\n");
    pane.showRendered(next);
    assertEquals(0, pane.images().live());
    pane.showImage(request, decoded.get());
    assertEquals(0, pane.images().live());
    assertNull(decoder.decode(new byte[] {1, 2}, request, null));
    pane.showImage(next.images().get(0), decoded.get());
    assertEquals(1, pane.images().live());
    PaneImages images = pane.images();
    shell.dispose();
    assertEquals(0, images.live());
  }

  @Test
  void aNewRenderKeepsTheTopLineAndScrollsAreReportedOnce() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      text.append("para ").append(i).append("\n\n");
    }
    pane.showRendered(content(text.toString()));
    flush();
    pane.scrollToLine(40);
    flush();
    assertEquals(40, pane.text().getTopIndex());
    assertEquals(List.of(), scrolled);
    pane.showRendered(content(text + "end\n"));
    flush();
    assertEquals(40, pane.text().getTopIndex());
    pane.text().setTopIndex(60);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !scrolled.isEmpty();
          }
        },
        "scroll reported");
    flush();
    assertEquals(List.of(60), scrolled);
    pane.showSizeNotice(2_000_000, 1_048_576);
    assertTrue(pane.noticeShown());
    pane.showRendered(content("x\n"));
    assertFalse(pane.noticeShown());
  }
}
