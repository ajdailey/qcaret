package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@link SymbolScanner#rescan} against {@link SymbolScanner#scanAll} of the same text, over random
 * edits to a text of several checkpoint blocks: comments and strings opened and closed across
 * lines, LF, CR LF and CR breaks split and joined, and lines over the length limit.
 */
class SymbolRescanTest {
  private static final String GRAMMAR =
      "<grammar id=\"mock\" name=\"Mock\"><symbols>"
          + "<symbol regex=\"\\bdef\\s+(\\w+)\" kind=\"function\"/>"
          + "<symbol regex=\"\\bclass\\s+(\\w+)\" kind=\"class\"/>"
          + "</symbols><context id=\"main\">"
          + "<span token=\"comment\" begin=\"/\\*\" end=\"\\*/\" multiLine=\"true\"/>"
          + "<span token=\"comment\" begin=\"#\"/>"
          + "<span token=\"string\" begin=\"&quot;\" end=\"&quot;\" multiLine=\"true\"/>"
          + "</context></grammar>";

  private static final int MAX_LINE_CHARS = 200;
  private static final int LINES = 4_000;
  private static final String[] BREAKS = {"\n", "\n", "\n", "\r\n", "\r"};

  private static String line(SplittableRandom random, int n) {
    return switch (random.nextInt(12)) {
      case 0 -> "/* open " + n;
      case 1 -> "close */ def after" + n;
      case 2 -> "\"open " + n;
      case 3 -> "end\" class Shut" + n;
      case 4 -> "# def hidden" + n;
      case 5 -> "def long" + n + " " + "x".repeat(MAX_LINE_CHARS);
      case 6, 7 -> "class Mock" + n + " { def inner" + n + " }";
      case 8, 9 -> "  def f" + n + "()";
      default -> "plain " + n;
    };
  }

  private static Document document(SplittableRandom random) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < LINES; i++) {
      text.append(line(random, i)).append(BREAKS[random.nextInt(BREAKS.length)]);
    }
    return Document.of(
        text.toString(),
        TextEncoding.of(StandardCharsets.UTF_8, false),
        LineEnding.LF,
        UndoLimits.DEFAULTS);
  }

  /** Applies a random edit; returns {prefix, suffix} chars it left unchanged. */
  private static int[] edit(Document document, SplittableRandom random, int n) {
    TextSnapshot text = document.text();
    int offset;
    int remove;
    String insert;
    switch (random.nextInt(5)) {
      case 0 -> {
        int line = random.nextInt(text.lineCount());
        offset = text.offsetOfLine(line);
        remove = text.lineLength(line);
        insert = line(random, n);
      }
      case 1 -> {
        offset = text.offsetOfLine(random.nextInt(text.lineCount()));
        remove = 0;
        insert = line(random, n) + BREAKS[random.nextInt(BREAKS.length)];
      }
      case 2 -> {
        int line = random.nextInt(text.lineCount() - 1);
        offset = text.offsetOfLine(line);
        remove = text.offsetOfLine(line + 1) - offset;
        insert = "";
      }
      case 3 -> {
        offset = random.nextInt(text.length() + 1);
        remove = 0;
        insert = random.nextBoolean() ? "\n" : "\r";
      }
      default -> {
        offset = random.nextInt(text.length());
        remove = 1;
        insert = "";
      }
    }
    document.apply(EditBatch.replace(offset, remove, insert));
    return new int[] {offset, document.text().length() - offset - insert.length()};
  }

  private static void rescansMatch(int maxSymbols, long seed) throws Exception {
    Syntax syntax = Tokens.compile(GRAMMAR);
    SymbolScanner scanner = new SymbolScanner(syntax, MAX_LINE_CHARS, maxSymbols);
    SplittableRandom random = new SplittableRandom(seed);
    Document document = document(random);
    CancellationToken cancel = new CancellationToken();
    SymbolScan kept = scanner.scanAll(document.text(), cancel);
    for (int step = 0; step < 150; step++) {
      int prefix = Integer.MAX_VALUE;
      int suffix = Integer.MAX_VALUE;
      int edits = 1 + random.nextInt(3);
      for (int i = 0; i < edits; i++) {
        int[] unchanged = edit(document, random, step * 4 + i);
        prefix = Math.min(prefix, unchanged[0]);
        suffix = Math.min(suffix, unchanged[1]);
      }
      kept = scanner.rescan(document.text(), kept, prefix, suffix, cancel);
      assertEquals(
          scanner.scanAll(document.text(), cancel).result(), kept.result(), "step " + step);
    }
  }

  @Test
  void aRescanAfterRandomEditsEqualsAScanFromTheFirstLine() throws Exception {
    rescansMatch(SymbolScanner.MAX_SYMBOLS, 11);
    rescansMatch(SymbolScanner.MAX_SYMBOLS, 12);
  }

  @Test
  void aTruncatedRescanAfterRandomEditsEqualsAScanFromTheFirstLine() throws Exception {
    rescansMatch(700, 21);
    rescansMatch(700, 22);
  }

  @Test
  void anEditAfterATruncatedScansStopReturnsItsSymbols() throws Exception {
    Syntax syntax = Tokens.compile(GRAMMAR);
    SymbolScanner scanner = new SymbolScanner(syntax, MAX_LINE_CHARS, 10);
    Document document = document(new SplittableRandom(31));
    CancellationToken cancel = new CancellationToken();
    SymbolScan kept = scanner.scanAll(document.text(), cancel);
    assertTrue(kept.result().truncated());
    int offset = document.text().offsetOfLine(LINES - 10);
    document.apply(EditBatch.replace(offset, 0, "def late\n"));
    SymbolScan after =
        scanner.rescan(
            document.text(), kept, offset, document.text().length() - offset - 9, cancel);
    assertSame(kept.result(), after.result());
  }

  @Test
  void aScanIsChargedForItsSymbolsCheckpointsAndTokenizer() throws Exception {
    Syntax syntax = Tokens.compile(GRAMMAR);
    SymbolScan scan =
        new SymbolScanner(syntax, MAX_LINE_CHARS, SymbolScanner.MAX_SYMBOLS)
            .scanAll(document(new SplittableRandom(41)).text(), new CancellationToken());
    long names = 0;
    for (SymbolScanner.Symbol s : scan.result().symbols()) {
      names += s.name().length();
    }
    int symbols = scan.result().symbols().size();
    assertEquals(SymbolScanner.SYMBOL_BYTES * (long) symbols + 2 * names, scan.result().bytes());
    assertEquals(
        scan.result().bytes()
            + 8L * scan.lines.length
            + 4L * scan.skipped.length
            + SymbolScan.TOKENIZER_BYTES,
        scan.bytes());
  }
}
