package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Incremental highlighting against tokenizing from scratch: after random edits the engine holds the
 * same tokens and fold regions as a new one, and a converging edit retokenizes only its lines.
 */
class SyntaxEngineTest {
  private static final long FAR = Long.MAX_VALUE / 4;
  private static final String[] SNIPPETS = {
    "/*", "*/", "\"", "{", "}", "\n", "\r\n", "x", " int a = 1; ", "// c", "\n}\n", "'"
  };

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  /** Runs the engine until idle; returns the last regions it produced. */
  private static List<int[]> finish(SyntaxEngine engine) {
    List<int[]> regions = null;
    for (int i = 0; i < 100_000 && !engine.idle(); i++) {
      SyntaxEngine.Slice slice = engine.run(System.nanoTime() + FAR, new CancellationToken());
      if (slice.regions() != null) {
        regions = slice.regions();
      }
    }
    assertTrue(engine.idle());
    return regions;
  }

  /** Returns every line's tokens, through a viewport over the whole text. */
  private static Map<Integer, List<Integer>> allTokens(SyntaxEngine engine) {
    engine.request(0, engine.text().lineCount() - 1);
    SyntaxEngine.Slice slice = engine.run(System.nanoTime() + FAR, new CancellationToken());
    Map<Integer, List<Integer>> out = new HashMap<Integer, List<Integer>>();
    for (int i = 0; i < slice.lines().length; i++) {
      List<Integer> tokens = new ArrayList<Integer>();
      for (int v : slice.tokens()[i]) {
        tokens.add(v);
      }
      out.put(slice.lines()[i], tokens);
    }
    return out;
  }

  private static String mockJava(int classes) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < classes; i++) {
      text.append("/** Mock ")
          .append(i)
          .append(" */\nclass Mock")
          .append(i)
          .append(" {\n")
          .append("  String s = \"text\"; // note\n  void run() {\n    if (x) {\n")
          .append("      call(")
          .append(i)
          .append(");\n    }\n  }\n}\n");
    }
    return text.toString();
  }

  @Test
  void randomEditsLeaveTheSameTokensAndRegionsAsTokenizingAgain() {
    for (long seed = 1; seed <= 12; seed++) {
      randomEdits(seed, false);
    }
  }

  @Test
  void randomEditsBetweenPartialPassesOfRandomLengthLeaveTheSameTokensAndRegions() {
    for (long seed = 1; seed <= 600; seed++) {
      randomEdits(seed, true);
    }
  }

  @Test
  void randomEditsOverNestedAndCountedSpansLeaveTheSameTokensAndRegions() {
    String[] lua = {"--[==[", "]==]", "[[", "]]", "{", "}", "\n", "function f()", " end ", "--"};
    String[] rust = {"/*", "*/", "r#\"", "\"#", "{", "}", "\n", "r##\"", "\"##", "x"};
    StringBuilder luaText = new StringBuilder();
    StringBuilder rustText = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      luaText.append("--[=[ Mock ").append(i).append(" ]=]\nfunction m").append(i);
      luaText.append("(t)\n  local s = [[a\n]] .. \"x\"\n  if t then return { } end\nend\n");
      rustText.append("/* Mock /* ").append(i).append(" */ */\nfn m").append(i);
      rustText.append("() {\n  let s = r#\"a\n\"#;\n  if t { call(); }\n}\n");
    }
    for (long seed = 1; seed <= 100; seed++) {
      randomEdits("lua", luaText.toString(), lua, seed, true);
      randomEdits("rust", rustText.toString(), rust, seed, true);
    }
  }

  @Test
  void randomEditsOverHereDocumentsLeaveTheSameTokensAndRegions() {
    String[] ruby = {"<<~EOS", "EOS", "<<A <<B", "\nA\n", "\nB\n", "\n", "#{", "}", " x ", "#"};
    String[] shell = {"<<EOF", "\nEOF\n", "<<-'A' <<B", "\n\tA\n", "\nB\n", "\n", "\"", "$(", ")"};
    StringBuilder rubyText = new StringBuilder();
    StringBuilder shellText = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      rubyText.append("def m").append(i).append("\n  s = <<~EOS.strip + f(x) # note\n");
      rubyText.append("    Mock #{i}\n  EOS\n  t = <<A + <<B\na\nA\nb\nB\nend\n");
      shellText.append("m").append(i).append("() {\n  cat <<EOF | wc -l # note\n");
      shellText.append("Mock $HOME\nEOF\n  x=$(cat <<-'A'\n\ta\n\tA\n  )\n}\n");
    }
    for (long seed = 1; seed <= 100; seed++) {
      randomEdits("ruby", rubyText.toString(), ruby, seed, true);
      randomEdits("shell", shellText.toString(), shell, seed, true);
    }
  }

  /**
   * Applies seeded random edits to 240 lines of Java; after some edits runs a partial pass, after
   * others finishes the pass and compares with a new engine.
   *
   * @param lineStops true to stop a partial pass after a seeded random line count, false at a 50 us
   *     deadline
   */
  private static void randomEdits(long seed, boolean lineStops) {
    randomEdits("java", mockJava(20), SNIPPETS, seed, lineStops);
  }

  /** As {@link #randomEdits(long, boolean)} for a grammar, a text and the snippets inserted. */
  private static void randomEdits(
      String grammar, String text, String[] snippets, long seed, boolean lineStops) {
    Syntax syntax = Tokens.BUILT_IN.syntax(grammar);
    Random random = new Random(seed);
    Random stops = new Random(-seed);
    Document document = document(text);
    final List<DocumentChange> changes = new ArrayList<DocumentChange>();
    document.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            changes.add(change);
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not used.
          }
        });
    SyntaxEngine engine = new SyntaxEngine(syntax, document.text(), 200);
    finish(engine);
    String where = "seed " + seed + " step ";
    for (int step = 0; step < 60; step++) {
      int length = document.text().length();
      List<Replacement> batch = new ArrayList<Replacement>();
      int at = random.nextInt(length + 1);
      int remove = random.nextInt(3) == 0 ? Math.min(length - at, random.nextInt(40)) : 0;
      batch.add(new Replacement(at, remove, snippets[random.nextInt(snippets.length)]));
      if (random.nextBoolean() && at > 10) {
        batch.add(new Replacement(random.nextInt(at - 5), 0, snippets[random.nextInt(4)]));
      }
      try {
        document.apply(EditBatch.of(EditBatch.Kind.COMMAND, batch));
      } catch (IllegalArgumentException e) {
        continue;
      }
      DocumentChange change = changes.get(changes.size() - 1);
      engine.edit(change.edits(), document.text());
      if (random.nextInt(4) == 0) {
        List<int[]> regions = finish(engine);
        SyntaxEngine fresh = new SyntaxEngine(syntax, document.text(), 200);
        List<int[]> expected = finish(fresh);
        assertNotNull(regions, where + step);
        assertEquals(expected.size(), regions.size(), where + step);
        for (int i = 0; i < expected.size(); i++) {
          assertArrayEquals(expected.get(i), regions.get(i), where + step);
        }
        assertEquals(allTokens(fresh), allTokens(engine), where + step);
      } else if (random.nextBoolean()) {
        if (lineStops) {
          int stop = 1 + stops.nextInt(document.text().lineCount());
          engine.run(System.nanoTime() + FAR, stop, new CancellationToken());
        } else {
          engine.run(System.nanoTime() + 50_000, new CancellationToken());
        }
      }
    }
  }

  @Test
  void anEditBeforeAStoppedFrontierDoesNotConvergeBeforeIt() {
    Syntax java = Tokens.BUILT_IN.syntax("java");
    Document document = document("class A {\nint a;\nint b;\nint c;\nvoid f() {\n}\n}\n");
    final List<DocumentChange> changes = new ArrayList<DocumentChange>();
    document.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            changes.add(change);
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not used.
          }
        });
    SyntaxEngine engine = new SyntaxEngine(java, document.text(), 10_000);
    finish(engine);
    document.apply(EditBatch.replace(document.text().offsetOfLine(2), 0, "/*"));
    engine.edit(changes.get(0).edits(), document.text());
    // Lines 3 and 4 now start in the comment; line 5 still holds the code state.
    engine.run(System.nanoTime() + FAR, 3, new CancellationToken());
    assertEquals(4, engine.frontier());
    document.apply(EditBatch.replace(0, 0, "x"));
    engine.edit(changes.get(1).edits(), document.text());
    // Line 4's stored state matches the new one; line 5's predates the first edit.
    List<int[]> regions = finish(engine);
    SyntaxEngine fresh = new SyntaxEngine(java, document.text(), 10_000);
    assertEquals(finish(fresh).size(), regions.size());
    assertEquals(allTokens(fresh), allTokens(engine));
  }

  @Test
  void anEditBetweenACrAndAnLfShiftsTheLinesAfterItByItsLineDelta() {
    Syntax java = Tokens.BUILT_IN.syntax("java");
    String text = "class A {\nint a;\r\nvoid f() {\n}\n}\n";
    Document document = document(text);
    final List<DocumentChange> changes = new ArrayList<DocumentChange>();
    document.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            changes.add(change);
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not used.
          }
        });
    SyntaxEngine engine = new SyntaxEngine(java, document.text(), 10_000);
    finish(engine);
    // CR, CR LF, LF: two lines more; lineAt at the offset counts the first CR only afterwards.
    document.apply(EditBatch.replace(text.indexOf('\r') + 1, 0, "\r\n"));
    engine.edit(changes.get(0).edits(), document.text());
    List<int[]> regions = finish(engine);
    List<int[]> expected = finish(new SyntaxEngine(java, document.text(), 10_000));
    assertEquals(expected.size(), regions.size());
    for (int i = 0; i < expected.size(); i++) {
      assertArrayEquals(expected.get(i), regions.get(i));
    }
  }

  @Test
  void anEditThatConvergesRetokenizesOnlyItsLines() {
    Document document = document(mockJava(2000));
    SyntaxEngine engine = new SyntaxEngine(Tokens.BUILT_IN.syntax("java"), document.text(), 10_000);
    finish(engine);
    final List<DocumentChange> changes = new ArrayList<DocumentChange>();
    document.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            changes.add(change);
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not used.
          }
        });
    int line = 5_000;
    document.apply(EditBatch.replace(document.text().offsetOfLine(line) + 2, 0, "y"));
    engine.edit(changes.get(0).edits(), document.text());
    SyntaxEngine.Slice slice = engine.run(System.nanoTime() + FAR, new CancellationToken());
    // The edited line and one line on each side.
    assertEquals(line - 1, slice.from());
    assertEquals(line + 2, slice.to());
    assertEquals(document.text().lineCount(), engine.frontier());
    assertNotNull(slice.regions());
    assertEquals(6000, slice.regions().size());

    document.apply(EditBatch.replace(document.text().offsetOfLine(line), 0, "/*"));
    engine.edit(changes.get(1).edits(), document.text());
    slice = engine.run(System.nanoTime() + FAR, new CancellationToken());
    // The comment runs to the next class's doc comment, where the states meet again.
    assertEquals(line - 1, slice.from());
    assertEquals(line + 5, slice.to());
    assertTrue(slice.regions().size() < 6000);
  }

  @Test
  void viewLinesComeAtOnceAndAgainWhenTheFrontierPassesThem() {
    Document document = document(mockJava(500));
    SyntaxEngine engine = new SyntaxEngine(Tokens.BUILT_IN.syntax("java"), document.text(), 10_000);
    engine.setViewport(1, 3000, 3005);
    engine.request(3000, 3005);
    SyntaxEngine.Slice first = engine.run(System.nanoTime() - 1, new CancellationToken());
    assertEquals(3000, first.lines()[0]);
    assertEquals(3005, first.lines()[5]);
    assertEquals(0, first.from());
    assertEquals(1, first.to());
    SyntaxEngine.Slice rest = engine.run(System.nanoTime() + FAR, new CancellationToken());
    assertEquals(6, rest.lines().length);
    assertEquals(3000, rest.lines()[0]);
    assertNull(rest.regions());
    assertTrue(rest.to() == 3006);
    SyntaxEngine.Slice end = engine.run(System.nanoTime() + FAR, new CancellationToken());
    assertNotNull(end.regions());
    assertTrue(end.done());
    assertTrue(engine.run(System.nanoTime() + FAR, new CancellationToken()).isEmpty());
  }

  @Test
  void overLongLinesStayPlainAndAreCounted() {
    String text = "int a;\n" + "x".repeat(50) + "\n" + "int " + "y".repeat(60) + ";\n";
    SyntaxEngine engine =
        new SyntaxEngine(Tokens.BUILT_IN.syntax("java"), document(text).text(), 40);
    engine.request(0, 3);
    SyntaxEngine.Slice slice = engine.run(System.nanoTime() + FAR, new CancellationToken());
    assertEquals(2, slice.overLong());
    assertEquals(0, slice.tokens()[1].length);
    assertEquals(0, slice.tokens()[2].length);
    assertTrue(slice.tokens()[0].length > 0);
  }

  @Test
  void markerFoldingPairsTagsOutsideComments() {
    String xml = "<root>\n  <a x=\"1\">\n    <b/>\n    <!-- <c> -->\n  </a>\n</root>\n";
    SyntaxEngine engine =
        new SyntaxEngine(Tokens.BUILT_IN.syntax("xml"), document(xml).text(), 10_000);
    List<int[]> regions = finish(engine);
    assertEquals(2, regions.size());
    assertArrayEquals(new int[] {0, 5}, regions.get(0));
    assertArrayEquals(new int[] {1, 4}, regions.get(1));
  }

  @Test
  void bracketFoldingSkipsStringsAndKeepsTheLargestRegionPerLine() {
    String java = "class A {{\n  String s = \"{\";\n  }\n}\n";
    SyntaxEngine engine =
        new SyntaxEngine(Tokens.BUILT_IN.syntax("java"), document(java).text(), 10_000);
    List<int[]> regions = finish(engine);
    assertEquals(1, regions.size());
    assertArrayEquals(new int[] {0, 3}, regions.get(0));
  }
}
