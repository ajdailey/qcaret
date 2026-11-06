package io.github.ajdailey.qcaret.app.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Pagination over document text, without a printer. */
class PrintLayoutTest {
  private static final int[] NONE = new int[0];
  private static final CancellationToken GO = new CancellationToken();

  private static TextSnapshot text(String chars) {
    return Document.of(
            chars,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS)
        .text();
  }

  private static PrintSource source(String chars, boolean numbers) {
    return new PrintSource("name", text(chars), NONE, 4, numbers, null, 10_000);
  }

  private static PrintLayout layout(PrintSource source, int columns, int rows) {
    return PrintLayout.of(source, false, new PrintTarget.Frame(columns, rows), GO);
  }

  private static String row(PrintTarget.Row row) {
    StringBuilder out = new StringBuilder();
    for (PrintTarget.Span span : row.spans()) {
      while (out.length() < span.column()) {
        out.append('?');
      }
      out.append(span.text());
    }
    return out.toString();
  }

  private static List<String> rows(PrintLayout layout, int page) {
    List<String> out = new ArrayList<String>();
    for (PrintTarget.Row row : layout.page(page, GO).rows()) {
      out.add(row(row));
    }
    return out;
  }

  private static String lines(int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      out.append("line").append(i).append('\n');
    }
    return out.toString();
  }

  @Test
  void pagesBreakAtTheRowCountAndTheLastPageIsPartial() {
    PrintLayout layout = layout(source(lines(7), false), 20, 3);
    assertEquals(3, layout.pageCount());
    assertEquals(List.of("line1", "line2", "line3"), rows(layout, 1));
    assertEquals(List.of("line4", "line5", "line6"), rows(layout, 2));
    assertEquals(List.of("line7"), rows(layout, 3));
    assertEquals(3, layout.page(3, GO).total());
  }

  @Test
  void aTextFillingItsLastPageAddsNoBlankPage() {
    assertEquals(2, layout(source(lines(6), false), 20, 3).pageCount());
    assertEquals(2, layout(source("a\nb\nc\nd\ne\nf", false), 20, 3).pageCount());
  }

  @Test
  void emptyLinesPrintAsRowsAndAnEmptyTextIsOnePage() {
    assertEquals(List.of("a", "", "b"), rows(layout(source("a\n\nb\n", false), 20, 9), 1));
    PrintLayout empty = layout(source("", true), 20, 9);
    assertEquals(1, empty.pageCount());
    assertEquals(List.of(""), rows(empty, 1));
  }

  @Test
  void aLongLineWrapsByColumnAcrossPagesAndNumbersOnlyItsFirstRow() {
    PrintLayout layout = layout(source("abcdefghij\nz\n", true), 6, 2);
    PrintTarget.Page first = layout.page(1, GO);
    assertEquals(2, first.gutter());
    assertEquals(List.of("abcd", "efgh"), rows(layout, 1));
    assertEquals(1, first.rows().get(0).line());
    assertEquals(0, first.rows().get(1).line());
    assertEquals(List.of("ij", "z"), rows(layout, 2));
    PrintTarget.Page second = layout.page(2, GO);
    assertEquals(0, second.rows().get(0).line());
    assertEquals(2, second.rows().get(1).line());
  }

  @Test
  void aLineExactlyTheWidthAddsNoEmptyRow() {
    assertEquals(List.of("abcd", "e"), rows(layout(source("abcd\ne", false), 4, 9), 1));
  }

  @Test
  void tabsExpandAtTheTabWidthAndStopAtTheRowEnd() {
    assertEquals(List.of("a   b       c"), rows(layout(source("a\tb\t\tc", false), 20, 9), 1));
    assertEquals(List.of("abcd    ", "    e"), rows(layout(source("abcd\t\te", false), 8, 9), 1));
    PrintSource wide = new PrintSource("n", text("\tx"), NONE, 8, false, null, 10_000);
    assertEquals(List.of("        x"), rows(layout(wide, 20, 9), 1));
  }

  @Test
  void aWideCharThatDoesNotFitMovesToTheNextRow() {
    assertEquals(List.of("abc", "一一", "😀"), rows(layout(source("abc一一😀", false), 4, 9), 1));
    PrintLayout narrow = layout(source("一一", false), 1, 9);
    assertEquals(List.of("一", "一"), rows(narrow, 1));
  }

  @Test
  void aSelectionPrintsItsPartsWithDocumentLineNumbers() {
    String chars = "first line\nsecond line\nthird line\nfourth\n";
    int[] selected = {6, 17, 23, 28, 34, 41};
    PrintSource source = new PrintSource("n", text(chars), selected, 4, true, null, 10_000);
    PrintLayout layout = PrintLayout.of(source, true, new PrintTarget.Frame(40, 9), GO);
    assertEquals(1, layout.pageCount());
    assertEquals(List.of("line", "second", "third", "fourth"), rows(layout, 1));
    List<PrintTarget.Row> rows = layout.page(1, GO).rows();
    assertEquals(1, rows.get(0).line());
    assertEquals(2, rows.get(1).line());
    assertEquals(3, rows.get(2).line());
    assertEquals(4, rows.get(3).line());
  }

  @Test
  void twoSelectionsOnOneLinePrintAsTwoRows() {
    int[] selected = {0, 2, 4, 6};
    PrintSource source = new PrintSource("n", text("abcdefgh\n"), selected, 4, false, null, 100);
    PrintLayout layout = PrintLayout.of(source, true, new PrintTarget.Frame(40, 9), GO);
    assertEquals(List.of("ab", "ef"), rows(layout, 1));
  }

  @Test
  void aTextLargerThanEveryBlockPaginatesCompletely() {
    int count = 400_000;
    String chars = lines(count);
    assertTrue(chars.length() > 4 << 20);
    PrintLayout layout = layout(source(chars, true), 40, 50);
    assertEquals(count / 50, layout.pageCount());
    PrintTarget.Page last = layout.page(layout.pageCount(), GO);
    assertEquals(count, last.rows().get(49).line());
    assertEquals("line" + count, row(last.rows().get(49)));
  }

  @Test
  void aLineLargerThanEveryBlockWrapsCompletely() {
    int length = 3 << 20;
    PrintLayout layout = layout(source("x".repeat(length), false), 64, 64);
    assertEquals(length / 64 / 64, layout.pageCount());
    assertEquals("x".repeat(64), rows(layout, layout.pageCount()).get(63));
  }

  @Test
  void aCanceledTokenStopsPagination() {
    final CancellationToken cancel = new CancellationToken();
    cancel.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            PrintLayout.of(source("a\n", false), false, new PrintTarget.Frame(9, 9), cancel);
          }
        });
  }

  private static Syntax java() {
    return GrammarSet.of(
            GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>())
        .syntax("java");
  }

  private static TokenRole role(PrintTarget.Row row, String text) {
    for (PrintTarget.Span span : row.spans()) {
      if (span.text().contains(text)) {
        return span.role();
      }
    }
    throw new AssertionError(text + " not in " + row);
  }

  @Test
  void tokensCarryTheirStateAcrossLinesPagesAndWrappedRows() {
    String chars = "/* open\nstill comment\nclosed */\nint value;\n";
    PrintSource source = new PrintSource("n", text(chars), NONE, 4, false, java(), 10_000);
    PrintLayout layout = PrintLayout.of(source, false, new PrintTarget.Frame(40, 1), GO);
    assertEquals(4, layout.pageCount());
    assertEquals(TokenRole.COMMENT, role(layout.page(2, GO).rows().get(0), "still"));
    assertEquals(TokenRole.TYPE, role(layout.page(4, GO).rows().get(0), "int"));
    PrintLayout wrapped =
        PrintLayout.of(source, false, new PrintTarget.Frame(5, 99), new CancellationToken());
    PrintTarget.Page page = wrapped.page(1, GO);
    assertEquals(TokenRole.COMMENT, role(page.rows().get(3), "comm"));
  }

  @Test
  void aSkippedPageStillHighlightsFromTheDocumentStart() {
    String chars = "/*\n\n\n*/ int x;\n";
    PrintSource source = new PrintSource("n", text(chars), NONE, 4, false, java(), 10_000);
    PrintLayout layout = PrintLayout.of(source, false, new PrintTarget.Frame(40, 1), GO);
    PrintTarget.Row row = layout.page(4, GO).rows().get(0);
    assertEquals(TokenRole.COMMENT, role(row, "*/"));
    assertEquals(TokenRole.TYPE, role(row, "int"));
  }

  @Test
  void aSpanKeepsItsColumnAfterATabAndAWideChar() {
    PrintSource source = new PrintSource("n", text("一\tint x;"), NONE, 4, false, java(), 100);
    PrintLayout layout = PrintLayout.of(source, false, new PrintTarget.Frame(40, 9), GO);
    for (PrintTarget.Span span : layout.page(1, GO).rows().get(0).spans()) {
      if (span.text().equals("int")) {
        assertEquals(4, span.column());
        return;
      }
    }
    throw new AssertionError("no int span");
  }
}
