package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The document grammar's fold regions, indent rules, pairs and tokens reaching each view: through a
 * grammar switch, Reload Grammars and the highlighter's passes.
 */
class GrammarWiringTest {
  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private void loadGrammars() throws InterruptedException {
    final GrammarLibrary library = harness.application.grammars();
    final Object before = library.set();
    harness.application.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null && library.set() != before;
          }
        },
        "grammars read");
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    harness.await(condition, what);
  }

  private void awaitLanguage(final String name) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return states.status().language().equals(name);
          }
        },
        "language " + name);
  }

  private void awaitRegions(final EditorController editor, final int count)
      throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() == count;
          }
        },
        count + " fold regions, was " + editor.projection().foldRegionCount());
  }

  private EditorController open(String name, String text) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
    return harness.application.documents().activeEditor();
  }

  private static int[] shownBrackets(EditorController editor) {
    return ((FakeView.FakeTab) editor.view()).shown.brackets;
  }

  private static int caret(EditorController editor) {
    return editor.selections().selections().primary().caret();
  }

  @Test
  void bracketGrammarsFoldByTheHighlighterAndOthersByIndentation() throws Exception {
    loadGrammars();
    EditorController editor = open("A.java", "class A {\nint x;\n}\n");
    awaitLanguage("Java");
    // Indentation finds no region in unindented text; the grammar's brackets find one.
    awaitRegions(editor, 1);
    assertEquals(2, editor.projection().foldEnd(0));
    harness.application.setSyntax("python");
    awaitRegions(editor, 0);
    harness.application.setSyntax("java");
    awaitRegions(editor, 1);
    loadGrammars();
    awaitLanguage("Java");
    editor
        .document()
        .apply(EditBatch.replace(editor.document().text().length(), 0, "class B {\n}\n"));
    awaitRegions(editor, 2);
    // A split view's controller takes the same grammar.
    harness.application.execute(CommandId.SPLIT_VERTICAL);
    awaitRegions(harness.application.documents().activeEditor(), 2);
  }

  @Test
  void indentRulesAndPairsFollowTheGrammar() throws Exception {
    loadGrammars();
    EditorController editor = open("a.py", "if x:");
    awaitLanguage("Python");
    editor.document().setInsertSpaces(true);
    editor.document().setTabWidth(4);
    editor.selections().set(SelectionSet.caret(5));
    editor.newline();
    assertEquals("if x:\n    ", editor.document().text().toString());
    assertEquals(')', editor.pairs().closer('('));

    harness.application.setSyntax("plain");
    awaitLanguage("Plain Text");
    editor.newline();
    assertEquals("if x:\n    \n    ", editor.document().text().toString());
    assertEquals('\'', editor.pairs().closer('\''));

    harness.application.setSyntax("markdown");
    awaitLanguage("Markdown");
    assertEquals('`', editor.pairs().closer('`'));
    harness.application.setSyntax("html");
    awaitLanguage("HTML");
    assertEquals(0, editor.pairs().closer('('));
    assertTrue(editor.bracketPairs().isBracket('<'));
  }

  @Test
  void bracketMatchingSkipsBracketsInStringsOnceTokensAreKnown() throws Exception {
    String text = "f(\")\") + g(1);\n";
    final EditorController editor = open("B.java", text);
    // Before the grammars are read the document is plain and every bracket counts.
    editor.selections().set(SelectionSet.caret(1));
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    assertEquals(4, caret(editor));

    loadGrammars();
    awaitLanguage("Java");
    editor.viewportChanged(0, text.length());
    final SyntaxHighlighter syntax =
        (SyntaxHighlighter) harness.application.documents().activeFoldRegions();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return syntax.tokens(0) != null;
          }
        },
        "tokens of line 0");
    editor.selections().set(SelectionSet.caret(1));
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    assertEquals(6, caret(editor));
    editor.selections().set(SelectionSet.caret(1));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Arrays.equals(new int[] {1, 2, 5, 6}, shownBrackets(editor));
          }
        },
        "bracket highlight past the string");
    // The caret beside the string's bracket shows no pair.
    editor.selections().set(SelectionSet.caret(3));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shownBrackets(editor).length == 0;
          }
        },
        "no bracket highlight");
  }
}
