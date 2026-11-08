package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Grammar fold regions through the controllers: an indentation grammar's regions merged with its
 * string spans, and Fold All over a large Markdown file's sections and fences.
 */
class GrammarFoldingTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    final GrammarLibrary library = harness.application.grammars();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private EditorController open(String name, String text, final int regions) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text));
    final EditorController editor = harness.application.documents().activeEditor();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.compiledSyntax() != null
                && editor.projection().foldRegionCount() == regions;
          }
        },
        regions + " fold regions");
    return editor;
  }

  @Test
  void anIndentationGrammarFoldsItsStringSpansToo() throws Exception {
    DisplayProjection p =
        open("mock.py", "\"\"\"\nMock module.\n\"\"\"\ndef f():\n    return 1\n", 2).projection();
    assertEquals(2, p.foldEnd(0));
    assertEquals(4, p.foldEnd(3));
  }

  @Test
  void foldAllCollapsesEveryMarkdownSectionAndFence() throws Exception {
    int blocks = 10_000;
    StringBuilder text = new StringBuilder();
    StringBuilder heads = new StringBuilder();
    for (int i = 0; i < blocks; i++) {
      text.append("# Head ").append(i).append("\n\nText.\n\n## Sub\n\nMore.\n```\n# code\n```\n\n");
      heads.append("# Head ").append(i).append('\n');
    }
    final DisplayProjection p = open("mock.md", text.toString(), blocks * 3).projection();
    assertEquals(10, p.foldEnd(0));
    assertEquals(10, p.foldEnd(4));
    assertEquals(9, p.foldEnd(7));
    harness.application.execute(CommandId.FOLD_ALL);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return p.collapsedCount() == blocks * 3;
          }
        },
        "every region collapsed");
    assertEquals(heads + "\n", p.textRange(0, p.length()));
  }
}
