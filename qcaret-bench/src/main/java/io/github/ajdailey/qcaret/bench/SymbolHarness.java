package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.command.Fuzzy;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SymbolScan;
import io.github.ajdailey.qcaret.core.syntax.SymbolScanner;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Go to Symbol on generated 1,000,000-line documents: what a cache miss costs on the worker, what a
 * hit leaves on the UI thread, and what a cached result holds. Arguments: any of the {@link Shape}
 * names; none runs all. Runs at the shipped launchers' heap as {@link StorageHarness} does.
 *
 * <ul>
 *   <li>{@code first}: a scan at the default limits, as {@code DocumentSymbols} runs it; the first
 *       round apart from the median of the rest.
 *   <li>{@code cached}: the {@link Fuzzy} filter and sort of the cached symbols for an empty and a
 *       three-char query, the work a hit leaves.
 *   <li>{@code edit}: one-line edits near the top and at the middle, each followed by {@link
 *       SymbolScanner#rescan} of the previous scan and by a scan of the whole document, and a check
 *       that the two results are equal.
 *   <li>{@code heap}: live heap a {@link SymbolScan} holds against {@link SymbolScan#bytes}.
 * </ul>
 */
public final class SymbolHarness {
  /** Lines of each generated document. */
  static final int LINES = 1_000_000;

  private static final int EARLY_LINE = 1005;
  private static final int SCAN_ROUNDS = 5;
  private static final int FILTER_ROUNDS = 30;

  /** A generated document and its grammar. */
  public enum Shape {
    /** Java with a definition every 7 lines; the scan stops at the symbol count. */
    JAVA("java", 7),
    /** Java with a method every 200 lines; the scan reads to the end. */
    JAVA_SPARSE("java", 200),
    /** Markdown with a heading every 120 lines; the scan reads to the end. */
    MARKDOWN("markdown", 120);

    private final String grammar;
    private final int every;

    Shape(String grammar, int every) {
      this.grammar = grammar;
      this.every = every;
    }

    /** Returns the text: {@link #LINES} lines, a definition starting every {@code every}. */
    String generate() {
      StringBuilder out = new StringBuilder(LINES * 32);
      int line = 0;
      int n = 0;
      while (line < LINES) {
        if (grammar.equals("markdown")) {
          line += markdown(out, n, Math.min(every, LINES - line));
        } else {
          line += java(out, n, Math.min(every, LINES - line));
        }
        n++;
      }
      return out.toString();
    }

    private static int markdown(StringBuilder out, int n, int lines) {
      out.append("## Mock section ").append(n).append('\n');
      for (int i = 1; i < lines; i++) {
        if (i % 40 == 1 && i + 2 < lines) {
          out.append("```\n# not a heading ").append(i).append("\n```\n");
          i += 2;
        } else {
          out.append("- item ").append(i).append(" with `code` and *emphasis* text\n");
        }
      }
      return lines;
    }

    private static int java(StringBuilder out, int n, int lines) {
      out.append("  public String mock").append(n).append("(String value) {\n");
      for (int i = 1; i < lines - 1; i++) {
        if (i % 3 == 0) {
          out.append("    // void hidden").append(i).append("() {}\n");
        } else {
          out.append("    value = \"text \" + value + ").append(i).append(";\n");
        }
      }
      if (lines > 1) {
        out.append("  }\n");
      }
      return lines;
    }
  }

  private SymbolHarness() {}

  /**
   * Runs the harness.
   *
   * @param args the shapes
   * @throws IOException when the relaunch cannot start
   * @throws InterruptedException when interrupted while waiting
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (StorageHarness.heapOption(options) == null
        && !"jvm".equals(System.getProperty("qcaret.bench.heap"))) {
      System.exit(StorageHarness.relaunch(SymbolHarness.class, options, args));
    }
    System.out.printf(
        "max heap %d MiB, args %s%n", Runtime.getRuntime().maxMemory() / Corpus.MIB, options);
    GrammarSet set =
        GrammarSet.of(
            GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());
    List<Shape> shapes = new ArrayList<Shape>();
    for (String a : args) {
      shapes.add(Shape.valueOf(a));
    }
    if (shapes.isEmpty()) {
      shapes.addAll(Arrays.asList(Shape.values()));
    }
    for (Shape shape : shapes) {
      run(shape, set.syntax(shape.grammar));
    }
  }

  private static void run(Shape shape, Syntax syntax) {
    Document document =
        Document.of(
            shape.generate(),
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    long[] times = new long[SCAN_ROUNDS];
    SymbolScanner.Result result = null;
    for (int i = 0; i < SCAN_ROUNDS; i++) {
      long t0 = System.nanoTime();
      result = scan(syntax, document);
      times[i] = System.nanoTime() - t0;
    }
    int last = result.symbols().get(result.symbols().size() - 1).line();
    System.out.printf(
        Locale.ROOT,
        "%s first: %d symbols, truncated %b, last at line %d; first round %.0f ms, median %.0f ms%n",
        shape,
        result.symbols().size(),
        result.truncated(),
        last + 1,
        times[0] / 1e6,
        median(Arrays.copyOfRange(times, 1, SCAN_ROUNDS)) / 1e6);

    long[] empty = new long[FILTER_ROUNDS];
    long[] typed = new long[FILTER_ROUNDS];
    long sink = 0;
    for (int i = 0; i < FILTER_ROUNDS; i++) {
      long t0 = System.nanoTime();
      sink += filter(result.symbols(), "");
      long t1 = System.nanoTime();
      sink += filter(result.symbols(), "mk5");
      empty[i] = t1 - t0;
      typed[i] = System.nanoTime() - t1;
    }
    System.out.printf(
        Locale.ROOT,
        "%s cached: filter and sort, empty query median %.2f ms, \"mk5\" median %.2f ms (sink %d)%n",
        shape,
        median(empty) / 1e6,
        median(typed) / 1e6,
        sink);

    CancellationToken cancel = new CancellationToken();
    SymbolScanner scanner =
        new SymbolScanner(syntax, SyntaxEngine.DEFAULT_MAX_LINE_CHARS, SymbolScanner.MAX_SYMBOLS);
    SymbolScan kept = scanner.scanAll(document.text(), cancel);
    for (int at : new int[] {EARLY_LINE, LINES / 2 + 5}) {
      long[] rescan = new long[SCAN_ROUNDS];
      long[] whole = new long[SCAN_ROUNDS];
      int differ = 0;
      for (int i = 0; i < SCAN_ROUNDS; i++) {
        int line = at + i * 3;
        int offset = document.text().offsetOfLine(line);
        String text = "    int added" + i + " = 1;";
        document.apply(EditBatch.replace(offset, document.text().lineLength(line), text));
        int suffix = document.text().length() - offset - text.length();
        long t0 = System.nanoTime();
        kept = scanner.rescan(document.text(), kept, offset, suffix, cancel);
        long t1 = System.nanoTime();
        SymbolScan full = scanner.scanAll(document.text(), cancel);
        whole[i] = System.nanoTime() - t1;
        rescan[i] = t1 - t0;
        if (!full.result().equals(kept.result())) {
          differ++;
        }
      }
      System.out.printf(
          Locale.ROOT,
          "%s edit at line %d: rescan median %.2f ms, whole document median %.0f ms, %d of %d"
              + " rescans differ from the whole scan%n",
          shape,
          at + 1,
          median(rescan) / 1e6,
          median(whole) / 1e6,
          differ,
          SCAN_ROUNDS);
    }

    result = null;
    kept = null;
    long base = StorageHarness.liveHeap();
    kept = scanner.scanAll(document.text(), cancel);
    long used = StorageHarness.liveHeap() - base;
    System.out.printf(
        Locale.ROOT,
        "%s heap: %d symbols, %d live bytes (%.1f per symbol), charge %d (live / charge %.2f)%n",
        shape,
        kept.result().symbols().size(),
        used,
        used / (double) kept.result().symbols().size(),
        kept.bytes(),
        used / (double) kept.bytes());
  }

  private static SymbolScanner.Result scan(Syntax syntax, Document document) {
    return new SymbolScanner(syntax, SyntaxEngine.DEFAULT_MAX_LINE_CHARS, SymbolScanner.MAX_SYMBOLS)
        .scan(document.text(), new CancellationToken());
  }

  /** Scores and sorts the names as Quick Open's symbol rows do; returns the match count. */
  private static int filter(List<SymbolScanner.Symbol> symbols, String query) {
    List<int[]> keys = new ArrayList<int[]>();
    for (int i = 0; i < symbols.size(); i++) {
      int score = Fuzzy.score(query, symbols.get(i).name());
      if (score >= 0) {
        keys.add(new int[] {score, i});
      }
    }
    keys.sort(
        new Comparator<int[]>() {
          @Override
          public int compare(int[] a, int[] b) {
            return a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[1], b[1]);
          }
        });
    return keys.size();
  }

  private static long median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }
}
