package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.CodeBlock;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Markdown preview render time on generated documents of 100 KiB, 1 MiB and 10 MiB: {@code render},
 * the parse and model build of {@link MarkdownRenderer}, and {@code colors}, the code blocks'
 * {@link SyntaxEngine} pass the preview controller adds on the same worker. Two warm-up rounds,
 * then the median and maximum of 5. Arguments: sizes in KiB; none runs the three. Runs at the
 * shipped launchers' heap as {@link StorageHarness} does.
 *
 * <p>The document repeats a seeded section: headings, paragraphs with emphasis, code spans and
 * links, a nested list with task items, a quote, a 4-row table and a 6-line Java code block.
 */
public final class MarkdownHarness {
  private static final int WARMUP = 2;
  private static final int ROUNDS = 5;

  private MarkdownHarness() {}

  /**
   * Runs the harness.
   *
   * @param args sizes in KiB
   * @throws IOException when the relaunch cannot start
   * @throws InterruptedException when interrupted while waiting
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (StorageHarness.heapOption(options) == null
        && !"jvm".equals(System.getProperty("qcaret.bench.heap"))) {
      System.exit(StorageHarness.relaunch(MarkdownHarness.class, options, args));
    }
    System.out.printf(
        "max heap %d MiB, args %s%n", Runtime.getRuntime().maxMemory() / Corpus.MIB, options);
    GrammarSet grammars =
        GrammarSet.of(
            GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());
    List<String> sizes = args.length == 0 ? List.of("100", "1024", "10240") : Arrays.asList(args);
    MarkdownRenderer renderer = new MarkdownRenderer();
    for (String size : sizes) {
      TextSnapshot text = RopeStorage.of(document(Integer.parseInt(size) * 1024)).snapshot();
      long[] render = new long[ROUNDS];
      long[] colors = new long[ROUNDS];
      RenderedMarkdown rendered = null;
      for (int round = -WARMUP; round < ROUNDS; round++) {
        long start = System.nanoTime();
        rendered = renderer.render(text, null, new CancellationToken());
        long parsed = System.nanoTime();
        int tokens = colors(rendered, grammars);
        long end = System.nanoTime();
        if (round >= 0) {
          render[round] = parsed - start;
          colors[round] = end - parsed;
        }
        if (tokens == 0) {
          throw new IllegalStateException("no code tokens");
        }
      }
      System.out.printf(
          "%6d KiB, %7d chars, %6d rendered lines, %5d code blocks: render median %s max %s;"
              + " colors median %s max %s%n",
          Integer.parseInt(size),
          text.length(),
          rendered.lineCount(),
          rendered.codeBlocks().size(),
          millis(median(render)),
          millis(max(render)),
          millis(median(colors)),
          millis(max(colors)));
    }
  }

  /** Tokenizes every code block as the preview controller does; returns the token count. */
  private static int colors(RenderedMarkdown rendered, GrammarSet grammars) {
    int count = 0;
    for (CodeBlock block : rendered.codeBlocks()) {
      String id = block.language() == null ? null : grammars.forLabel(block.language());
      Syntax syntax = id == null ? null : grammars.syntax(id);
      if (syntax == null) {
        continue;
      }
      int last = block.firstLine() + block.lineCount() - 1;
      int start = rendered.lineStart(block.firstLine());
      int end =
          last + 1 < rendered.lineCount()
              ? rendered.lineStart(last + 1) - 1
              : rendered.text().length();
      SyntaxEngine engine =
          new SyntaxEngine(
              syntax,
              RopeStorage.of(rendered.text().substring(start, end)).snapshot(),
              SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
      engine.request(0, block.lineCount() - 1);
      SyntaxEngine.Slice slice = engine.run(System.nanoTime(), new CancellationToken());
      for (int[] line : slice.tokens()) {
        count += line.length / 3;
      }
    }
    return count;
  }

  /** A seeded document of at least {@code chars} chars. */
  static String document(int chars) {
    Random random = new Random(42);
    StringBuilder out = new StringBuilder(chars + 2048);
    int section = 0;
    while (out.length() < chars) {
      section++;
      out.append("## Section ").append(section).append(" of the mock guide\n\n");
      for (int p = 0; p < 2; p++) {
        out.append("The *mock* text of paragraph ").append(p).append(" holds `code` and a ");
        out.append("[link](docs/page").append(random.nextInt(100)).append(".md) and **strong");
        out.append(" words** that wrap\nover two source lines with ~~struck~~ text.\n\n");
      }
      out.append("- item one\n  - nested [x] task\n- [ ] open task\n- item three\n\n");
      out.append("> A quoted line with *emphasis*.\n\n");
      out.append("| Name | Value | Note |\n|:-----|------:|:----:|\n");
      for (int r = 0; r < 4; r++) {
        out.append("| row").append(r).append(" | ").append(random.nextInt(1000));
        out.append(" | **b** |\n");
      }
      out.append("\n```java\nclass Mock").append(section).append(" {\n");
      out.append("  // a comment\n  int value = ").append(random.nextInt(100)).append(";\n");
      out.append("  String name = \"mock\";\n}\n```\n\n");
    }
    return out.toString();
  }

  private static long median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static long max(long[] values) {
    long max = 0;
    for (long v : values) {
      max = Math.max(max, v);
    }
    return max;
  }

  private static String millis(long nanos) {
    return String.format("%.1f ms", nanos / 1e6);
  }
}
