package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineTransforms;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UI thread time of Fold All, Unfold All and a Sort Lines edit applied with every region collapsed,
 * on generated Java ({@link SymbolHarness.Shape#JAVA}) whose fold regions come from the Java
 * grammar. The projection's listener reads the replaced text before each display change and the
 * inserted text after it, as {@code StyledText} does on Windows for its accessible text event. A
 * Fold All's measuring and the sort run off the clock, as they run on a worker in the editor.
 */
public final class FoldHarness {
  /** Lines of the Fold All document, as the G1 gate's {@code Big.java}. */
  static final int BIG_LINES = 480_005;

  /** Lines of the sorted document, as the G1 gate's {@code m1.java}. */
  static final int SORT_LINES = 1_000_001;

  private static final int ROUNDS = 5;
  private static final int SORT_ROUNDS = 3;

  /** Reads each change's text as the widget's accessible text event does. */
  static final class AccessibleReader implements DisplayProjection.Listener {
    private final DisplayProjection projection;
    boolean on = true;
    long chars;

    AccessibleReader(DisplayProjection projection) {
      this.projection = projection;
      projection.addListener(this);
    }

    @Override
    public void changing(DisplayProjection.DisplayChange change) {
      if (on) {
        chars += projection.textRange(change.start(), change.replaceChars()).length();
      }
    }

    @Override
    public void changed(DisplayProjection.DisplayChange change) {
      if (on) {
        chars += projection.textRange(change.start(), change.newChars()).length();
      }
    }
  }

  private FoldHarness() {}

  /**
   * Runs the harness.
   *
   * @param args {@code foldall}, {@code sort}, or both when empty
   * @throws IOException when the relaunch cannot start
   * @throws InterruptedException when interrupted while waiting
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (StorageHarness.heapOption(options) == null
        && !"jvm".equals(System.getProperty("qcaret.bench.heap"))) {
      System.exit(StorageHarness.relaunch(FoldHarness.class, options, args));
    }
    System.out.printf(
        "max heap %d MiB, args %s%n", Runtime.getRuntime().maxMemory() / Corpus.MIB, options);
    List<String> cases = Arrays.asList(args);
    if (cases.isEmpty() || cases.contains("foldall")) {
      foldAll();
    }
    if (cases.isEmpty() || cases.contains("sort")) {
      sort();
    }
  }

  private static void foldAll() {
    Highlighted h = new Highlighted(BIG_LINES);
    long[] fold = new long[ROUNDS];
    long[] unfold = new long[ROUNDS];
    for (int i = 0; i < ROUNDS; i++) {
      fold[i] = h.foldAll();
      long t0 = System.nanoTime();
      h.projection.unfoldAll();
      unfold[i] = System.nanoTime() - t0;
    }
    System.out.printf(
        Locale.ROOT,
        "foldall %,d lines, %,d regions: Fold All %s ms, Unfold All %s ms, %,d chars read%n",
        BIG_LINES,
        h.regions.size(),
        millis(fold),
        millis(unfold),
        h.reader.chars);
    h.close();
  }

  private static void sort() {
    sort(true);
    sort(false);
  }

  /** Runs the sort case; without {@code read} the listener reads nothing: the model's share. */
  private static void sort(boolean read) {
    long[] fold = new long[SORT_ROUNDS];
    long[] sort = new long[SORT_ROUNDS];
    int regions = 0;
    for (int i = 0; i < SORT_ROUNDS; i++) {
      Highlighted h = new Highlighted(SORT_LINES);
      h.reader.on = read;
      regions = h.regions.size();
      Edit edit =
          LineTransforms.sort(
              h.document.text(),
              SelectionSet.caret(0),
              LineTransforms.Order.ASCENDING,
              new CancellationToken());
      fold[i] = h.foldAll();
      long t0 = System.nanoTime();
      h.document.apply(edit.batch());
      sort[i] = System.nanoTime() - t0;
      h.close();
    }
    System.out.printf(
        Locale.ROOT,
        "sort %,d lines, %,d regions, text read %b: Fold All %s ms, then the sort's edit %s ms%n",
        SORT_LINES,
        regions,
        read,
        millis(fold),
        millis(sort));
  }

  /** A generated Java document, its fold regions offered to a projection with a reader. */
  private static final class Highlighted {
    final Document document;
    final DisplayProjection projection;
    final AccessibleReader reader;
    final List<int[]> regions;

    Highlighted(int lines) {
      GrammarSet set =
          GrammarSet.of(
              GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());
      document =
          Document.of(
              SymbolHarness.Shape.JAVA.generate(lines),
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS);
      SyntaxEngine engine =
          new SyntaxEngine(
              set.syntax("java"), document.text(), SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
      CancellationToken token = new CancellationToken();
      List<int[]> found = null;
      while (!engine.idle()) {
        SyntaxEngine.Slice slice = engine.run(Long.MAX_VALUE, token);
        if (slice.regions() != null) {
          found = slice.regions();
        }
      }
      if (found == null || found.isEmpty()) {
        throw new IllegalStateException("no fold regions");
      }
      regions = found;
      projection =
          new DisplayProjection(
              document, DisplayProjection.layout(document.text(), 0, new CancellationToken()));
      projection.setFoldRegions(regions);
      reader = new AccessibleReader(projection);
    }

    /** Returns the UI thread's share of a Fold All: the request and the install, in ns. */
    long foldAll() {
      long t0 = System.nanoTime();
      DisplayProjection.FoldAll request = projection.foldAllRequest();
      long t1 = System.nanoTime();
      DisplayProjection.computeFoldAll(request);
      long t2 = System.nanoTime();
      if (projection.installFoldAll(request) != DisplayProjection.FoldAllOutcome.APPLIED) {
        throw new IllegalStateException("Fold All not applied");
      }
      return t1 - t0 + System.nanoTime() - t2;
    }

    void close() {
      projection.dispose();
    }
  }

  private static String millis(long[] times) {
    StringBuilder out = new StringBuilder();
    for (long t : times) {
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append(String.format(Locale.ROOT, "%.0f", t / 1e6));
    }
    return out.toString();
  }
}
