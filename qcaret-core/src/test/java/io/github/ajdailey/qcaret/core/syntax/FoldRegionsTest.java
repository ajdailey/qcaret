package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fold regions from sections, folding spans and region markers, merged with the grammar's own mode,
 * on each changed built-in grammar's sample.
 */
class FoldRegionsTest {
  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  /** Runs the engine until idle and returns the last regions as {@code start-end} entries. */
  private static List<String> finish(SyntaxEngine engine) {
    List<int[]> regions = null;
    for (int i = 0; i < 100_000 && !engine.idle(); i++) {
      SyntaxEngine.Slice slice =
          engine.run(System.nanoTime() + (1L << 40), new CancellationToken());
      if (slice.regions() != null) {
        regions = slice.regions();
      }
    }
    assertTrue(engine.idle());
    List<String> out = new ArrayList<String>();
    for (int[] r : regions) {
      out.add(r[0] + "-" + r[1]);
    }
    return out;
  }

  private static List<String> regions(String grammar, String text) {
    return finish(
        new SyntaxEngine(
            Tokens.BUILT_IN.syntax(grammar),
            document(text).text(),
            SyntaxEngine.DEFAULT_MAX_LINE_CHARS));
  }

  private static String lines(String... lines) {
    return String.join("\n", lines);
  }

  @Test
  void mergeKeepsTheLargestRegionPerStartLineInOrder() {
    List<int[]> a = List.of(new int[] {0, 3}, new int[] {2, 4}, new int[] {7, 9});
    List<int[]> b = List.of(new int[] {0, 5}, new int[] {2, 3}, new int[] {5, 5});
    List<String> out = new ArrayList<String>();
    for (int[] r : FoldRegions.merge(a, b)) {
      out.add(r[0] + "-" + r[1]);
    }
    assertEquals(List.of("0-5", "2-4", "7-9"), out);
    assertEquals(a, FoldRegions.merge(a, List.of()));
  }

  @Test
  void javaFoldsJavadocTextBlocksAndNestedRegionsBesideBrackets() {
    assertEquals(
        List.of("0-2", "3-13", "4-9", "6-8", "10-12"),
        regions(
            "java",
            lines(
                "/**",
                " * Mock.",
                " */",
                "class Mock {",
                "  // #region fields",
                "  int a;",
                "  //#region inner",
                "  int b;",
                "  //#endregion",
                "  // #endregion",
                "  String s = \"\"\"",
                "      text",
                "      \"\"\";",
                "}")));
  }

  @Test
  void unpairedRegionMarkersAndMarkersInCommentsOrStringsFoldNothing() {
    assertEquals(
        List.of("1-4"),
        regions(
            "java",
            lines(
                "// #endregion stray",
                "/*",
                "// #region in a comment",
                "// #endregion in a comment",
                "*/",
                "String s = \"\"; // #region after code",
                "// #region never closed",
                "int a;")));
  }

  @Test
  void aBlockCommentEditedInAndOutMovesItsRegion() {
    Document document = document(lines("int a;", "int b;", "int c;", "int d;"));
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
    SyntaxEngine engine =
        new SyntaxEngine(
            Tokens.BUILT_IN.syntax("c"), document.text(), SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
    assertEquals(List.of(), finish(engine));
    document.apply(EditBatch.replace(document.text().offsetOfLine(1), 0, "/*"));
    engine.edit(changes.get(0).edits(), document.text());
    assertEquals(List.of("1-3"), finish(engine));
    document.apply(EditBatch.replace(document.text().offsetOfLine(2) + 6, 0, "*/"));
    engine.edit(changes.get(1).edits(), document.text());
    assertEquals(List.of("1-2"), finish(engine));
    document.apply(EditBatch.replace(document.text().offsetOfLine(1), 2, ""));
    engine.edit(changes.get(2).edits(), document.text());
    assertEquals(List.of(), finish(engine));
  }

  @Test
  void markdownSectionsNestByLevelAndSkipHeadersInFencesAndComments() {
    assertEquals(
        List.of("0-14", "2-6", "3-5", "7-10", "9-10", "11-14", "12-14", "15-18", "19-21"),
        regions(
            "markdown",
            lines(
                "# One",
                "",
                "## Two",
                "```",
                "# in a fence",
                "```",
                "",
                "Setext",
                "------",
                "<!--",
                "# in a comment -->",
                "## Three",
                "### Four",
                "text",
                "",
                "Top",
                "===",
                "text",
                "",
                "# Last",
                "text",
                "end",
                "",
                "")));
  }

  @Test
  void aSectionAtTheEndRunsToTheLastNonBlankLine() {
    assertEquals(
        List.of("0-4", "3-4"),
        regions("markdown", lines("# One", "text", "", "## Two", "x", "", "")));
  }

  @Test
  void anUnderlineUnderABlankLineOrAHeaderIsNoHeader() {
    assertEquals(
        List.of("0-5", "3-5"),
        regions("markdown", lines("# One", "", "---", "## Two", "---", "x")));
  }

  @Test
  void tomlTablesAndArraysOfTablesFold() {
    assertEquals(
        List.of("3-5", "6-9"),
        regions(
            "toml",
            lines(
                "title = \"mock\"",
                "x = 1",
                "",
                "[server]",
                "host = \"h\"",
                "",
                "[[items]]",
                "name = \"a\"",
                "list = [",
                "  1 ]")));
  }

  @Test
  void iniSectionsFold() {
    assertEquals(
        List.of("0-2", "3-4"),
        regions("ini", lines("[one]", "a = 1", "; [not a section]", "[two]", "b = 2", "")));
  }

  @Test
  void diffFilesAndHunksFold() {
    assertEquals(
        List.of("0-10", "2-10", "4-6", "7-10", "11-16", "12-16", "14-16"),
        regions(
            "diff",
            lines(
                "diff --git a/one b/one",
                "index 1..2 100644",
                "--- a/one",
                "+++ b/one",
                "@@ -1,2 +1,2 @@",
                "--- removed line",
                "+added",
                "@@ -9 +9 @@",
                " same",
                "-x",
                "+y",
                "diff --git a/two b/two",
                "--- a/two",
                "+++ b/two",
                "@@ -1 +1 @@",
                "-a",
                "+b")));
  }

  @Test
  void dockerfileStagesFold() {
    assertEquals(
        List.of("0-3", "4-6"),
        regions(
            "dockerfile",
            lines(
                "FROM mock AS build",
                "RUN make \\",
                "  all",
                "",
                "from mock",
                "# FROM in a comment",
                "COPY --from=build /out /out",
                "")));
  }

  @Test
  void csharpAndVisualBasicRegionsFoldBesideTheirMode() {
    assertEquals(
        List.of("0-7", "1-5", "2-3"),
        regions(
            "csharp",
            lines(
                "class Mock {",
                "  #region Fields",
                "  /* a",
                "     b */",
                "  int a;",
                "  #endregion",
                "  int b;",
                "}")));
    assertEquals(
        List.of("0-3"),
        regions("vbnet", lines("#Region \"Mock\"", "Dim a = 1", "Dim b = 2", "#End Region")));
  }

  @Test
  void cAndCppPragmaRegionsFold() {
    String text =
        lines("#pragma region mock", "int a;", "/*", " */", "#pragma endregion", "int b;");
    assertEquals(List.of("0-4", "2-3"), regions("c", text));
    assertEquals(List.of("0-4", "2-3"), regions("cpp", text));
  }

  @Test
  void scriptAndKotlinCommentRegionsAndTemplatesFold() {
    String script = lines("//#region mock", "const s = `a", "b`;", "// #endregion");
    assertEquals(List.of("0-3", "1-2"), regions("javascript", script));
    assertEquals(List.of("0-3", "1-2"), regions("typescript", script));
    assertEquals(
        List.of("0-4", "1-3"),
        regions("kotlin", lines("// #region", "/* a", "/* b */", "*/", "// #endregion")));
  }

  @Test
  void aSpanWithFoldingOffFoldsNothing() {
    assertEquals(List.of(), regions("csv", lines("a,\"b", "c\",d", "e")));
    assertEquals(List.of(), regions("properties", lines("key = a \\", "  b \\", "  c")));
  }
}
