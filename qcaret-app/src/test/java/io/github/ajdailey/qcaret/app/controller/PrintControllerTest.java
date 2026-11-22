package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPrintSheet;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File > Print over a fake view and a fake printer: threads, outcomes and cleanup. */
class PrintControllerTest {
  /** A printer that records pages, as text rows, on the job's thread. */
  private static final class FakeTarget implements PrintTarget {
    final List<Page> pages = new CopyOnWriteArrayList<Page>();
    final List<MarkdownPrintSheet.Page> renderedPages =
        new CopyOnWriteArrayList<MarkdownPrintSheet.Page>();
    final List<String> rendered = new CopyOnWriteArrayList<String>();
    final Map<String, Boolean> images = new ConcurrentHashMap<String, Boolean>();
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch inPage = new CountDownLatch(1);
    volatile boolean gate;
    volatile boolean failStart;
    volatile boolean failPage;
    volatile boolean selection;
    volatile int first = 1;
    volatile int last = Integer.MAX_VALUE;
    volatile int rows = 2;
    volatile int closes;
    volatile boolean finished;
    volatile Thread thread;

    @Override
    public boolean selectionOnly() {
      return selection;
    }

    @Override
    public int firstPage() {
      return first;
    }

    @Override
    public int lastPage() {
      return last;
    }

    @Override
    public Frame start(String name) {
      thread = Thread.currentThread();
      if (failStart) {
        throw new PrintFailure("mock failure", null);
      }
      return new Frame(40, rows);
    }

    @Override
    public MarkdownPrintSheet startMarkdown(String name, final MarkdownPreviewContent content) {
      thread = Thread.currentThread();
      if (failStart) {
        throw new PrintFailure("mock failure", null);
      }
      return new MarkdownPrintSheet() {
        @Override
        public Body body() {
          return new Body(1000, rows * 10, 2);
        }

        @Override
        public int width(int start, int end, boolean strong) {
          return (end - start) * 10;
        }

        @Override
        public int indent(int line) {
          return 0;
        }

        @Override
        public void image(MarkdownImageRequest request, byte[] bytes) {
          images.put(request.path().getFileName().toString(), bytes.length > 0);
        }

        @Override
        public int[] rows(int line, MarkdownTableLayout.Row table) {
          return new int[] {10};
        }

        @Override
        public void page(MarkdownPrintSheet.Page page) {
          String text = content.rendered().text();
          for (MarkdownPrintSheet.Placed placed : page.lines()) {
            int start = content.rendered().lineStart(placed.line());
            int end = text.indexOf('\n', start);
            rendered.add(text.substring(start, end < 0 ? text.length() : end));
          }
          renderedPages.add(page);
          pageDone();
        }
      };
    }

    @Override
    public void page(Page page) {
      if (failPage) {
        throw new PrintFailure("mock failure", null);
      }
      pages.add(page);
      pageDone();
    }

    private void pageDone() {
      inPage.countDown();
      if (gate) {
        try {
          assertTrue(release.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public void close() {
      closes++;
    }

    List<String> text() {
      List<String> out = new ArrayList<String>();
      for (Page page : pages) {
        for (Row row : page.rows()) {
          StringBuilder line = new StringBuilder();
          for (Span span : row.spans()) {
            line.append(span.text());
          }
          out.add(line.toString());
        }
      }
      return out;
    }
  }

  @TempDir Path temp;

  private Harness harness;
  private FakeTarget target;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    target = new FakeTarget();
    harness.view.printTarget = target;
  }

  @AfterEach
  void close() throws InterruptedException {
    target.release.countDown();
    harness.close();
  }

  private void open(String text) throws Exception {
    Path file = temp.resolve("sample.txt");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    harness.open(file);
  }

  private void print() {
    harness.application.execute(CommandId.PRINT);
  }

  private String notice() {
    List<String> notices = harness.view.notices;
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  private void awaitEnd() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return target.closes == 1 && harness.view.printRow == null;
          }
        },
        "print job end");
  }

  @Test
  void printHasNoDefaultKeyBecauseQuickOpenHoldsModP() {
    Keymap keymap = harness.application.keymap();
    assertEquals("", keymap.keysText(CommandId.PRINT));
    assertNotEquals("", keymap.keysText(CommandId.QUICK_OPEN));
  }

  @Test
  void aJobPrintsOnAPlatformWorkerAndReportsItsPages() throws Exception {
    open("a\nb\nc\n");
    print();
    assertEquals(UiText.PRINT_PREPARING.format("sample.txt"), harness.view.printRow);
    awaitEnd();
    assertEquals(List.of("a", "b", "c"), target.text());
    assertEquals(2, target.pages.size());
    assertTrue(target.finished);
    assertTrue(target.thread.getName().startsWith("qcaret-print-"));
    assertFalse(target.thread.isVirtual());
    assertEquals("sample.txt: 2 pages sent to the printer", notice());
  }

  @Test
  void theDocumentsGrammarColorsThePages() throws Exception {
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.application.documents().grammarChoices().isEmpty();
          }
        },
        "grammars");
    open("// note\n");
    harness.application.setSyntax("java");
    print();
    awaitEnd();
    assertEquals(TokenRole.COMMENT, target.pages.get(0).rows().get(0).spans().get(0).role());
  }

  @Test
  void aCanceledDialogPrintsNothing() throws Exception {
    open("a\n");
    harness.view.printTarget = null;
    print();
    harness.ui.runPending();
    assertNull(harness.view.printRow);
    assertEquals(0, target.closes);
    print();
    harness.ui.runPending();
    assertTrue(target.pages.isEmpty());
  }

  @Test
  void withoutADocumentThePrintCommandDoesNothing() {
    print();
    assertNull(harness.view.printRow);
    assertEquals(0, target.closes);
  }

  @Test
  void anEditDuringAJobDoesNotReachIt() throws Exception {
    open("a\nb\nc\nd\n");
    target.gate = true;
    print();
    assertTrue(target.inPage.await(20, TimeUnit.SECONDS));
    harness.application.documents().activeEditor().type("changed");
    assertTrue(
        harness.application.documents().activeDocument().text().toString().contains("changed"));
    target.release.countDown();
    awaitEnd();
    assertEquals(List.of("a", "b", "c", "d"), target.text());
  }

  @Test
  void cancelDiscardsTheJobAndClosesIt() throws Exception {
    open("a\nb\nc\nd\ne\n");
    target.gate = true;
    print();
    assertTrue(target.inPage.await(20, TimeUnit.SECONDS));
    harness.view.printCancel.run();
    target.release.countDown();
    awaitEnd();
    assertEquals(1, target.pages.size());
    assertFalse(target.finished);
    assertEquals(1, target.closes);
    assertNotEquals("sample.txt: 1 page sent to the printer", notice());
  }

  @Test
  void aFailureAtStartOrOnAPageEndsInANoticeAndClosesTheJob() throws Exception {
    open("a\n");
    target.failStart = true;
    print();
    awaitEnd();
    assertEquals(UiText.PRINT_FAILED.format("sample.txt"), notice());
    assertFalse(target.finished);

    FakeTarget second = new FakeTarget();
    target = second;
    harness.view.printTarget = second;
    second.failPage = true;
    print();
    awaitEnd();
    assertEquals(UiText.PRINT_FAILED.format("sample.txt"), notice());
    assertFalse(second.finished);
  }

  @Test
  void aSecondJobWhileOneRunsIsRefusedWithANotice() throws Exception {
    open("a\nb\n");
    target.gate = true;
    print();
    assertTrue(target.inPage.await(20, TimeUnit.SECONDS));
    print();
    assertEquals(UiText.PRINT_RUNNING.text(), notice());
    target.release.countDown();
    awaitEnd();
  }

  @Test
  void aPageRangePrintsItsPagesUnderTheWholeCount() throws Exception {
    open("a\nb\nc\nd\ne\n");
    target.first = 2;
    target.last = 99;
    print();
    awaitEnd();
    assertEquals(List.of("c", "d", "e"), target.text());
    assertEquals(2, target.pages.get(0).number());
    assertEquals(3, target.pages.get(0).total());
    assertEquals("sample.txt: 2 pages sent to the printer", notice());
  }

  @Test
  void aRangePastTheEndPrintsNothingAndSaysSo() throws Exception {
    open("a\n");
    target.first = 5;
    target.last = 6;
    print();
    awaitEnd();
    assertTrue(target.pages.isEmpty());
    assertFalse(target.finished);
    assertEquals("The document has 1 page; the page range is past the end", notice());
  }

  @Test
  void theSelectionPrintsWhenChosenAndAnEmptyOneEndsInANotice() throws Exception {
    open("alpha\nbeta\n");
    target.selection = true;
    print();
    harness.ui.runPending();
    assertEquals(1, target.closes);
    assertEquals(UiText.PRINT_NO_SELECTION.text(), notice());
    assertTrue(target.pages.isEmpty());

    FakeTarget second = new FakeTarget();
    target = second;
    harness.view.printTarget = second;
    second.selection = true;
    harness
        .application
        .documents()
        .activeEditor()
        .selections()
        .set(SelectionSet.of(List.of(Selection.of(2, 8)), 0));
    print();
    awaitEnd();
    assertEquals(List.of("pha", "be"), target.text());
  }

  private void select(int start, int end) {
    harness
        .application
        .documents()
        .activeEditor()
        .selections()
        .set(SelectionSet.of(List.of(Selection.of(start, end)), 0));
  }

  @Test
  void printSelectionNeedsASelectionAndHasNoDefaultKey() throws Exception {
    open("alpha\nbeta\n");
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.PRINT_SELECTION));
    assertTrue(CommandStates.EDIT_STATE.contains(CommandId.PRINT_SELECTION));
    assertEquals("", harness.application.keymap().keysText(CommandId.PRINT_SELECTION));
    harness.application.execute(CommandId.PRINT_SELECTION);
    harness.ui.runPending();
    assertEquals(UiText.PRINT_NO_SELECTION.text(), notice());
    assertEquals(0, target.closes);
    assertNull(harness.view.printRow);
    select(2, 8);
    assertTrue(states.enabled(CommandId.PRINT_SELECTION));
  }

  @Test
  void printSelectionPrintsTheSelectionWhateverTheDialogsScope() throws Exception {
    open("alpha\nbeta\n");
    select(2, 8);
    harness.application.execute(CommandId.PRINT_SELECTION);
    awaitEnd();
    assertEquals(List.of("pha", "be"), target.text());
  }

  @Test
  void printWithASelectionAndTheDialogsAllPagesPrintsTheWholeDocument() throws Exception {
    open("alpha\nbeta\n");
    select(2, 8);
    print();
    awaitEnd();
    assertEquals(List.of("alpha", "beta"), target.text());
  }

  @Test
  void aPageRangeUnderPrintSelectionCountsTheSelectionsPages() throws Exception {
    open("a\nb\nc\nd\ne\nf\n");
    select(2, 9);
    target.first = 2;
    target.last = 2;
    harness.application.execute(CommandId.PRINT_SELECTION);
    awaitEnd();
    assertEquals(List.of("d", "e"), target.text());
    assertEquals(2, target.pages.get(0).number());
    assertEquals(2, target.pages.get(0).total());
  }

  @Test
  void closingTheWindowCancelsTheJob() throws Exception {
    open("a\nb\nc\nd\n");
    target.gate = true;
    PrintController window =
        new PrintController(
            harness.application.documents(), harness.workers, harness.ui, harness.view);
    window.print();
    assertTrue(target.inPage.await(20, TimeUnit.SECONDS));
    window.dispose();
    target.release.countDown();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return target.closes == 1;
          }
        },
        "job closed");
    assertFalse(target.finished);
    assertEquals(1, target.pages.size());
  }

  /** Opens {@code text} as {@code sample.md} with the Markdown grammar. */
  private void openMarkdown(String text) throws Exception {
    loadGrammars();
    Path file = temp.resolve("sample.md");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    harness.open(file);
    harness.application.setSyntax("markdown");
  }

  private void loadGrammars() throws InterruptedException {
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.application.documents().grammarChoices().isEmpty();
          }
        },
        "grammars");
  }

  @Test
  void printRenderedMarkdownNeedsAMarkdownTabAndHasNoDefaultKey() throws Exception {
    loadGrammars();
    open("# not rendered\n");
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.PRINT_MARKDOWN));
    assertEquals("", harness.application.keymap().keysText(CommandId.PRINT_MARKDOWN));
    harness.application.execute(CommandId.PRINT_MARKDOWN);
    harness.ui.runPending();
    assertNull(harness.view.printRow);
    assertEquals(0, target.closes);
    harness.application.setSyntax("markdown");
    assertTrue(states.enabled(CommandId.PRINT_MARKDOWN));
  }

  @Test
  void printRenderedMarkdownPrintsTheWholeRenderingOnAPlatformWorker() throws Exception {
    openMarkdown("# Title\n\nSome *mock* text\n\n- item\n");
    select(2, 5);
    target.selection = true;
    harness.application.execute(CommandId.PRINT_MARKDOWN);
    awaitEnd();
    assertEquals(List.of("Title", "", "Some mock text", "", "item"), target.rendered);
    assertTrue(target.pages.isEmpty());
    assertEquals(3, target.renderedPages.size());
    assertTrue(target.finished);
    assertTrue(target.thread.getName().startsWith("qcaret-print-"));
    assertFalse(target.thread.isVirtual());
    assertEquals("sample.md: 3 pages sent to the printer", notice());
  }

  @Test
  void printRenderedMarkdownReadsLocalImagesButNotSvg() throws Exception {
    Files.write(temp.resolve("mock.png"), new byte[] {1, 2, 3});
    Files.writeString(temp.resolve("mock.svg"), "<svg/>", StandardCharsets.UTF_8);
    openMarkdown("![a](mock.png) ![b](mock.svg) ![c](missing.png)\n");
    harness.application.execute(CommandId.PRINT_MARKDOWN);
    awaitEnd();
    assertEquals(Boolean.TRUE, target.images.get("mock.png"));
    assertEquals(Boolean.FALSE, target.images.get("mock.svg"));
    assertEquals(Boolean.FALSE, target.images.get("missing.png"));
  }

  @Test
  void cancelDiscardsARenderedMarkdownJob() throws Exception {
    openMarkdown("one\n\ntwo\n\nthree\n\nfour\n");
    target.gate = true;
    harness.application.execute(CommandId.PRINT_MARKDOWN);
    assertTrue(target.inPage.await(20, TimeUnit.SECONDS));
    harness.view.printCancel.run();
    target.release.countDown();
    awaitEnd();
    assertEquals(1, target.renderedPages.size());
    assertFalse(target.finished);
    assertEquals(1, target.closes);
  }

  @Test
  void aPageRangeOfRenderedMarkdownPrintsItsPagesUnderTheWholeCount() throws Exception {
    openMarkdown("one\n\ntwo\n\nthree\n");
    target.first = 2;
    target.last = 2;
    harness.application.execute(CommandId.PRINT_MARKDOWN);
    awaitEnd();
    assertEquals(List.of("two", ""), target.rendered);
    assertEquals(2, target.renderedPages.get(0).number());
    assertEquals(3, target.renderedPages.get(0).total());
  }
}
