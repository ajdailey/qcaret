package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.editor.TokenCache;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.syntax.TokenType;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownPreview} with a fake view: debounce, stale drop, cancel on edit, the busy cursor,
 * the size limit, mode reset on a grammar change, scroll mapping, code colors, link clicks, and
 * Browser pages built on a worker with stale ones dropped.
 */
class MarkdownPreviewTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final GrammarSet GRAMMARS =
      GrammarSet.of(
          GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Workers workers =
      new Workers(
          2,
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
              failures.add(e);
            }
          });
  private final Busy busy = new Busy();
  private final FakeTab tab = new FakeTab();
  private final FakePane pane = new FakePane();
  private final MemoryBudget budget = new MemoryBudget(1L << 40, 100);

  @AfterEach
  void close() {
    assertTrue(workers.close(Duration.ofSeconds(5)));
    assertEquals(List.of(), failures);
  }

  /** Counts busy-cursor operations. */
  private static final class Busy implements ActivityIndicator {
    final AtomicInteger started = new AtomicInteger();
    final AtomicInteger active = new AtomicInteger();

    @Override
    public void setActive() {
      started.incrementAndGet();
      active.incrementAndGet();
    }

    @Override
    public void setIdle() {
      active.decrementAndGet();
    }
  }

  private final class FakeTab implements MarkdownPreview.Tab {
    final List<Integer> scrolled = new ArrayList<Integer>();
    final List<Path> files = new ArrayList<Path>();
    final List<String> urls = new ArrayList<String>();
    final List<String> notices = new ArrayList<String>();
    int lastTop = Integer.MAX_VALUE;
    Path folder;
    boolean browser;
    int browserFailures;
    volatile Palette palette = Palette.LIGHT;

    @Override
    public Path folder() {
      return folder;
    }

    @Override
    public ActivityIndicator busy() {
      return busy;
    }

    @Override
    public GrammarSet grammars() {
      return GRAMMARS;
    }

    @Override
    public int maxLineChars() {
      return SyntaxEngine.DEFAULT_MAX_LINE_CHARS;
    }

    @Override
    public int scrollSource(int line) {
      scrolled.add(line);
      return Math.min(line, lastTop);
    }

    @Override
    public void notice(String message) {
      notices.add(message);
    }

    @Override
    public void openFile(Path path) {
      files.add(path);
    }

    @Override
    public void openUrl(String url) {
      urls.add(url);
    }

    @Override
    public boolean browser() {
      return browser;
    }

    @Override
    public Palette palette() {
      return palette;
    }

    @Override
    public void browserFailed() {
      browserFailures++;
    }
  }

  /** Records display requests; fails a call off the UI thread. */
  private final class FakePane implements MarkdownPreviewView {
    final List<MarkdownViewMode> modes = new ArrayList<MarkdownViewMode>();
    final List<MarkdownPreviewContent> shown = new ArrayList<MarkdownPreviewContent>();
    final List<int[]> notices = new ArrayList<int[]>();
    final List<Integer> scrolls = new ArrayList<Integer>();
    final List<String> anchors = new ArrayList<String>();
    final List<int[]> selects = new ArrayList<int[]>();
    final FakeMarkdownPane delegate = new FakeMarkdownPane(null);

    @Override
    public void showMode(MarkdownViewMode mode) {
      ui.checkUiThread();
      modes.add(mode);
    }

    @Override
    public void showRendered(MarkdownPreviewContent content) {
      ui.checkUiThread();
      shown.add(content);
    }

    @Override
    public void showSizeNotice(int chars, int limit) {
      ui.checkUiThread();
      notices.add(new int[] {chars, limit});
    }

    @Override
    public void scrollToLine(int line) {
      ui.checkUiThread();
      scrolls.add(line);
    }

    @Override
    public void selectRendered(int start, int end) {
      ui.checkUiThread();
      selects.add(new int[] {start, end});
    }

    @Override
    public void scrollToAnchor(String id) {
      ui.checkUiThread();
      anchors.add(id);
    }

    @Override
    public MarkdownImageDecoder imageDecoder() {
      return delegate.imageDecoder();
    }

    @Override
    public void showImage(MarkdownImageRequest request, DecodedImage image) {
      ui.checkUiThread();
      delegate.showImage(request, image);
    }

    String lastText() {
      return shown.get(shown.size() - 1).rendered().text();
    }
  }

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  private MarkdownPreview preview(Document document, int limit) {
    MarkdownPreview p = unattached(document, limit, budget);
    p.grammarChanged(true);
    p.attach(pane);
    return p;
  }

  private MarkdownPreview unattached(Document document, int limit, MemoryBudget budget) {
    return new MarkdownPreview(
        document,
        workers,
        ui,
        new MarkdownRenderer(),
        tab,
        limit,
        budget,
        new MarkdownImages(workers, ui));
  }

  private void runUntil(BooleanSupplier condition) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND));
  }

  private void awaitShown(final int count) throws InterruptedException {
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.shown.size() >= count && busy.active.get() == 0;
          }
        });
  }

  @Test
  void switchingFromSourceRendersUnderTheBusyCursor() throws InterruptedException {
    MarkdownPreview p = preview(document("# Title\n\nText\n"), 1000);
    assertEquals(List.of(MarkdownViewMode.SOURCE), pane.modes);
    assertTrue(p.setMode(MarkdownViewMode.SPLIT));
    awaitShown(1);
    assertEquals("Title\n\nText", pane.lastText());
    assertEquals(1, busy.started.get());
    assertTrue(p.setMode(MarkdownViewMode.PREVIEW));
    ui.runPending();
    assertEquals(1, pane.shown.size());
    assertEquals(1, busy.started.get());
    assertEquals(
        List.of(MarkdownViewMode.SOURCE, MarkdownViewMode.SPLIT, MarkdownViewMode.PREVIEW),
        pane.modes);
    p.dispose();
  }

  @Test
  void editsRenderOnceAfterThePauseWithoutTheBusyCursor() throws InterruptedException {
    Document d = document("one\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    d.apply(EditBatch.replace(d.text().length(), 0, "two\n"));
    d.apply(EditBatch.replace(d.text().length(), 0, "three\n"));
    assertEquals(1, ui.queued());
    awaitShown(2);
    assertEquals("one two three", pane.lastText());
    assertEquals(2, pane.shown.size());
    assertEquals(1, busy.started.get());
    p.dispose();
  }

  @Test
  void editsInSourceRenderNothing() {
    Document d = document("one\n");
    MarkdownPreview p = preview(d, 1000);
    d.apply(EditBatch.replace(0, 0, "x"));
    assertEquals(0, ui.queued());
    ui.runPending();
    assertEquals(0, pane.shown.size());
    p.dispose();
  }

  @Test
  void aResultForAnOlderRevisionIsDropped() throws InterruptedException {
    final Document d = document("first\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    d.apply(EditBatch.replace(0, 5, "second"));
    ui.runQueued();
    final CancellationToken token = p.runningRender();
    assertNotNull(token);
    // Waits for the worker's result without running it, so it is still queued at the edit.
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return ui.queued() > 0;
              }
            },
            BOUND));
    d.apply(EditBatch.replace(0, 6, "third"));
    ui.runQueued();
    assertEquals(1, pane.shown.size());
    awaitShown(2);
    assertEquals("third", pane.lastText());
    assertEquals(2, pane.shown.size());
    p.dispose();
  }

  @Test
  void anEditCancelsTheRunningRender() {
    Document d = document("text\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    CancellationToken first = p.runningRender();
    assertNotNull(first);
    d.apply(EditBatch.replace(0, 0, "x"));
    assertTrue(first.isCanceled());
    assertNull(p.runningRender());
    p.dispose();
  }

  @Test
  void aDocumentOverTheLimitShowsTheNoticeUntilARenderIsRequested() throws InterruptedException {
    Document d = document("0123456789 over the limit\n");
    MarkdownPreview p = preview(d, 10);
    p.setMode(MarkdownViewMode.SPLIT);
    ui.runPending();
    assertEquals(1, pane.notices.size());
    assertEquals(d.text().length(), pane.notices.get(0)[0]);
    assertEquals(10, pane.notices.get(0)[1]);
    assertEquals(0, busy.started.get());
    assertNull(p.runningRender());
    d.apply(EditBatch.replace(0, 0, "x"));
    ui.runPending();
    assertEquals(2, pane.notices.size());
    assertEquals(0, pane.shown.size());
    p.renderRequested();
    awaitShown(1);
    assertEquals(1, busy.started.get());
    assertEquals("x0123456789 over the limit", pane.lastText());
    p.dispose();
  }

  @Test
  void aGrammarChangeAwayFromMarkdownReturnsToSource() throws InterruptedException {
    Document d = document("text\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.SPLIT);
    p.grammarChanged(false);
    assertEquals(MarkdownViewMode.SOURCE, p.mode());
    assertEquals(MarkdownViewMode.SOURCE, pane.modes.get(pane.modes.size() - 1));
    assertFalse(p.setMode(MarkdownViewMode.PREVIEW));
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return busy.active.get() == 0;
          }
        });
    assertEquals(0, pane.shown.size());
    p.dispose();
  }

  @Test
  void scrollsMapThroughTheSourceMapWithoutEcho() throws InterruptedException {
    Document d = document("# A\n\npara\n\n```\nx\ny\n```\n\nend\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.SPLIT);
    awaitShown(1);
    RenderedMarkdown r = pane.shown.get(0).rendered();
    p.sourceScrolled(6);
    assertEquals(List.of(r.renderedLine(6)), pane.scrolls);
    p.previewScrolled(r.renderedLine(6));
    assertEquals(List.of(), tab.scrolled);
    p.previewScrolled(2);
    assertEquals(List.of(r.sourceLine(2)), tab.scrolled);
    p.sourceScrolled(r.sourceLine(2));
    assertEquals(1, pane.scrolls.size());
    p.setMode(MarkdownViewMode.PREVIEW);
    p.sourceScrolled(0);
    assertEquals(1, pane.scrolls.size());
    p.dispose();
  }

  @Test
  void aMatchInPreviewScrollsToItsBlockAndSelectsItsText() throws InterruptedException {
    String text = "# A\n\npara\n\nA **needle** here.\n";
    MarkdownPreview p = preview(document(text), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    RenderedMarkdown r = pane.shown.get(0).rendered();
    int at = text.indexOf("needle");
    p.revealMatch(at, at + 6);
    assertEquals(List.of(r.renderedLine(4)), pane.scrolls);
    int rendered = r.text().indexOf("needle");
    assertEquals(List.of(List.of(rendered, rendered + 6)), selects());
    p.setMode(MarkdownViewMode.SPLIT);
    p.revealMatch(at, at + 6);
    assertEquals(1, pane.scrolls.size());
    assertEquals(1, pane.selects.size());
    p.dispose();
  }

  @Test
  void aMatchInBrowserContentScrollsWithoutASelection() throws InterruptedException {
    tab.browser = true;
    String text = "# A\n\npara needle\n";
    MarkdownPreview p = preview(document(text), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    int at = text.indexOf("needle");
    p.revealMatch(at, at + 6);
    assertEquals(List.of(pane.shown.get(0).rendered().renderedLine(2)), pane.scrolls);
    assertEquals(List.of(), pane.selects);
    p.dispose();
  }

  @Test
  void aMatchAfterAnEditWaitsForTheRenderOfThatRevision() throws InterruptedException {
    Document d = document("# A\n\nA needle here.\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    d.apply(EditBatch.replace(0, 3, "# Mock needle"));
    p.revealMatch(7, 13);
    assertEquals(List.of(), pane.scrolls);
    awaitShown(2);
    assertEquals(List.of(0), pane.scrolls);
    assertEquals(List.of(List.of(5, 11)), selects());
    p.dispose();
  }

  @Test
  void aHeldMatchIsDroppedByALaterEdit() throws InterruptedException {
    Document d = document("# A\n\nA needle here.\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    d.apply(EditBatch.replace(0, 3, "# Mock needle"));
    p.revealMatch(7, 13);
    d.apply(EditBatch.replace(0, 0, "x"));
    awaitShown(2);
    assertEquals(List.of(), pane.scrolls);
    assertEquals(List.of(), pane.selects);
    p.dispose();
  }

  private List<List<Integer>> selects() {
    List<List<Integer>> all = new ArrayList<List<Integer>>();
    for (int[] s : pane.selects) {
      all.add(List.of(s[0], s[1]));
    }
    return all;
  }

  @Test
  void codeBlocksAreColoredByTheGrammarTheirLanguageNames() throws InterruptedException {
    Document d = document("```java\n// note\nint x;\n```\n\n```mock-language\nint y;\n```\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    MarkdownPreviewContent content = pane.shown.get(0);
    int[] java = content.codeTokens().get(0);
    String text = content.rendered().text();
    boolean comment = false;
    for (int i = 0; i < java.length; i += 3) {
      comment |=
          text.substring(java[i], java[i + 1]).equals("// note")
              && java[i + 2] == TokenCache.role(TokenType.COMMENT.ordinal());
    }
    assertTrue(comment);
    assertEquals(0, content.codeTokens().get(1).length);
    p.dispose();
  }

  @Test
  void clicksFollowAnchorsFilesUrlsAndFootnotes() throws InterruptedException {
    tab.folder = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
    Document d =
        document(
            "[top](#part-two) [file](notes.md) [web](https://example.com) note[^n]\n\n"
                + "# Part two\n\n[^n]: The note.\n");
    MarkdownPreview p = preview(d, 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    RenderedMarkdown r = pane.shown.get(0).rendered();
    p.clicked(r.links().get(0).start());
    assertEquals(List.of(r.headingLine("part-two")), pane.scrolls);
    p.clicked(r.links().get(1).start());
    assertEquals(List.of(tab.folder.resolve("notes.md")), tab.files);
    p.clicked(r.links().get(2).start());
    assertEquals(List.of("https://example.com"), tab.urls);
    p.clicked(r.footnoteReferences().get(0).start());
    assertEquals(r.footnotes().get(0).line(), (int) pane.scrolls.get(1));
    p.clicked(r.text().indexOf(' '));
    assertEquals(2, pane.scrolls.size());
    p.dispose();
  }

  @Test
  void aClampedEditorScrollIsNotMappedBack() throws InterruptedException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append("para ").append(i).append("\n\n");
    }
    MarkdownPreview p = preview(document(text.toString()), 100_000);
    p.setMode(MarkdownViewMode.SPLIT);
    awaitShown(1);
    RenderedMarkdown r = pane.shown.get(0).rendered();
    tab.lastTop = 30;
    p.previewScrolled(r.lineCount() - 1);
    assertEquals(List.of(r.sourceLine(r.lineCount() - 1)), tab.scrolled);
    p.sourceScrolled(30);
    assertEquals(List.of(), pane.scrolls);
    p.sourceScrolled(31);
    assertEquals(List.of(r.renderedLine(31)), pane.scrolls);
    p.dispose();
  }

  @Test
  void aRequestedRenderAboveTheLimitChargesTheBudgetUntilReplaced() throws InterruptedException {
    MemoryBudget small = new MemoryBudget(1L << 30, 100);
    Document d = document("0123456789 over the limit\n");
    MarkdownPreview p = unattached(d, 10, small);
    p.grammarChanged(true);
    p.attach(pane);
    p.setMode(MarkdownViewMode.PREVIEW);
    p.renderRequested();
    awaitShown(1);
    long charge = MarkdownCharge.BYTES_PER_CHAR * d.text().length();
    assertEquals(charge, small.reserved());
    p.renderRequested();
    awaitShown(2);
    assertEquals(charge, small.reserved());
    p.dispose();
    assertEquals(0, small.reserved());
  }

  @Test
  void aRefusedReservationRendersNothingAndPostsANotice() throws InterruptedException {
    MemoryBudget tiny = new MemoryBudget(100, 100);
    MarkdownPreview p = unattached(document("0123456789 over the limit\n"), 10, tiny);
    p.grammarChanged(true);
    p.attach(pane);
    p.setMode(MarkdownViewMode.PREVIEW);
    p.renderRequested();
    assertNull(p.runningRender());
    assertEquals(List.of(UiText.MARKDOWN_NO_MEMORY.text()), tab.notices);
    assertEquals(0, tiny.reserved());
    p.dispose();
  }

  @Test
  void aGrammarReloadOvertakesTheRunningRenderAndRendersAgain() throws InterruptedException {
    MarkdownPreview p = preview(document("```java\nint x;\n```\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    CancellationToken first = p.runningRender();
    p.grammarsReloaded();
    assertTrue(first.isCanceled());
    assertNotNull(p.runningRender());
    awaitShown(1);
    p.grammarsReloaded();
    awaitShown(2);
    p.setMode(MarkdownViewMode.SOURCE);
    p.grammarsReloaded();
    assertNull(p.runningRender());
    p.dispose();
  }

  @Test
  void aRestoredModeWaitsForAMarkdownGrammar() throws InterruptedException {
    MarkdownPreview p = unattached(document("text\n"), 1000, budget);
    p.grammarChanged(false);
    p.attach(pane);
    p.restoreMode(MarkdownViewMode.SPLIT, true);
    assertEquals(MarkdownViewMode.SOURCE, p.mode());
    p.grammarChanged(true);
    assertEquals(MarkdownViewMode.SPLIT, p.mode());
    awaitShown(1);
    p.grammarChanged(false);
    p.restoreMode(MarkdownViewMode.PREVIEW, false);
    p.grammarChanged(true);
    assertEquals(MarkdownViewMode.SOURCE, p.mode());
    p.restoreMode(MarkdownViewMode.PREVIEW, false);
    assertEquals(MarkdownViewMode.PREVIEW, p.mode());
    p.dispose();
  }

  @Test
  void aResultAfterSwitchingToSourceIsDropped() throws InterruptedException {
    MarkdownPreview p = preview(document("text\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    p.setMode(MarkdownViewMode.SOURCE);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return busy.active.get() == 0;
          }
        });
    ui.runPending();
    assertEquals(0, pane.shown.size());
    assertNull(p.shown());
    p.dispose();
  }

  @Test
  void browserContentCarriesHtmlAndNoImageRequests() throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("# Title\n\n![x](x.png)\n"), 1000);
    p.setMode(MarkdownViewMode.SPLIT);
    awaitShown(1);
    MarkdownPreviewContent content = pane.shown.get(0);
    assertNotNull(content.html());
    assertTrue(content.html().body().contains("<h1 id=\"title\">Title</h1>"));
    assertEquals(List.of(), content.images());
    tab.browser = false;
    p.rendererChanged();
    awaitShown(2);
    assertNull(pane.shown.get(1).html());
    assertEquals(2, busy.started.get());
    p.dispose();
  }

  private static String page(MarkdownPreviewContent content, Palette palette) {
    return MarkdownPage.page("", content.html().body(), palette);
  }

  @Test
  void browserContentArrivesWithItsPageAndAPaletteChangeRebuildsOnlyThePage()
      throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("# Title\n\nText\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    MarkdownPreviewContent first = pane.shown.get(0);
    assertEquals(page(first, Palette.LIGHT), first.html().page());
    tab.palette = Palette.DARK;
    p.paletteChanged();
    assertEquals(1, pane.shown.size(), "the page builds on a worker");
    awaitShown(2);
    MarkdownPreviewContent second = pane.shown.get(1);
    assertEquals(page(first, Palette.DARK), second.html().page());
    assertTrue(second.rendered() == first.rendered(), "no new render");
    assertEquals(second, p.shown());
    assertEquals(1, busy.started.get());
    p.dispose();
  }

  @Test
  void aPageForAnOverriddenPaletteIsDropped() throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("# Title\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    MarkdownPreviewContent first = pane.shown.get(0);
    tab.palette = Palette.DARK;
    p.paletteChanged();
    tab.palette = Palette.LIGHT;
    p.paletteChanged();
    // Both pages wait on the UI queue; the first one's generation is overtaken.
    awaitQueued(2);
    ui.runQueued();
    assertEquals(2, pane.shown.size());
    assertEquals(page(first, Palette.LIGHT), pane.shown.get(1).html().page());
    p.dispose();
  }

  @Test
  void aRenderBuiltInAReplacedPaletteShowsOnlyTheRebuiltPage() throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("# Title\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    // The render started in Light; nothing shows yet, so the change has no page to rebuild.
    tab.palette = Palette.DARK;
    p.paletteChanged();
    awaitShown(1);
    MarkdownPreviewContent shown = pane.shown.get(0);
    assertEquals(page(shown, Palette.DARK), shown.html().page());
    ui.runPending();
    assertEquals(1, pane.shown.size());
    p.dispose();
  }

  @Test
  void aPageForContentNoLongerShownIsDropped() throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("# Title\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    MarkdownPreviewContent stale = pane.shown.get(0);
    p.rendererChanged();
    awaitQueued(1);
    // The new render waits on the UI queue; the page asked for now is for the content it replaces.
    tab.palette = Palette.DARK;
    p.paletteChanged();
    awaitQueued(2);
    ui.runQueued();
    awaitShown(2);
    ui.runPending();
    assertEquals(2, pane.shown.size());
    MarkdownPreviewContent last = pane.shown.get(1);
    assertTrue(last.rendered() != stale.rendered(), "the new render, rebuilt in Dark");
    assertEquals(page(last, Palette.DARK), last.html().page());
    p.dispose();
  }

  private void awaitQueued(final int count) throws InterruptedException {
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return ui.queued() == count;
              }
            },
            BOUND),
        "queued " + count + ", was " + ui.queued());
  }

  @Test
  void basicClicksOpenWebUrlsOnly() throws InterruptedException {
    MarkdownPreview p =
        preview(
            document(
                "[a](javascript:alert(1)) [b](smb://host/share) [c](//host/x)"
                    + " [d](FTP://example.invalid/f) [e](mailto:someone@example.invalid)\n"),
            1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    RenderedMarkdown r = pane.shown.get(0).rendered();
    for (int i = 0; i < r.links().size(); i++) {
      p.clicked(r.links().get(i).start());
    }
    assertEquals(5, r.links().size());
    assertEquals(List.of("FTP://example.invalid/f", "mailto:someone@example.invalid"), tab.urls);
    assertEquals(List.of(), tab.files);
    p.dispose();
  }

  @Test
  void browserLinksScrollToAnchorsOpenFilesAndWebUrlsOnly() throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("# Part two\n"), 1000);
    p.linkFollowed("about:blank#part-two");
    assertEquals(List.of(), pane.anchors);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    p.linkFollowed("about:blank#part%20two");
    p.linkFollowed("about:blank");
    Path file = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("a.md");
    p.linkFollowed(file.toUri().toString());
    Path spaced = file.resolveSibling("mock notes.txt");
    p.linkFollowed(MarkdownLinks.previewFileUrl(spaced));
    p.linkFollowed("https://example.invalid/x");
    p.linkFollowed("MAILTO:someone@example.invalid");
    p.linkFollowed("javascript:alert(1)");
    p.linkFollowed("data:text/html,x");
    p.linkFollowed("other.txt");
    assertEquals(List.of("part two"), pane.anchors);
    assertEquals(List.of(file, spaced), tab.files);
    assertEquals(List.of("https://example.invalid/x", "MAILTO:someone@example.invalid"), tab.urls);
    p.dispose();
  }

  @Test
  void aBrowserFailurePostsTheNoticeAndRendersAgain() throws InterruptedException {
    tab.browser = true;
    MarkdownPreview p = preview(document("text\n"), 1000);
    p.setMode(MarkdownViewMode.PREVIEW);
    awaitShown(1);
    tab.browser = false;
    p.browserFailed();
    awaitShown(2);
    assertEquals(1, tab.browserFailures);
    assertNull(pane.shown.get(1).html());
    p.dispose();
  }
}
