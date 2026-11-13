package io.github.ajdailey.qcaret.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.infra.Blackhole;

/** Each benchmark's setup at a small size and one call of each benchmark method, without JMH. */
class BenchmarkSetupTest {
  private static Blackhole blackhole() {
    return new Blackhole(
        "Today's password is swordfish. I understand instantiating Blackholes directly is"
            + " dangerous.");
  }

  @Test
  void buildBenchmark() {
    BuildBenchmark b = new BuildBenchmark();
    b.corpus = Corpus.MIXED;
    b.sizeMiB = 1;
    b.setUp();
    assertEquals(b.decode().length(), b.build().length());
  }

  @Test
  void editBenchmark() {
    EditBenchmark b = new EditBenchmark();
    EditBenchmark.Fresh fresh = new EditBenchmark.Fresh();
    fresh.corpus = Corpus.ASCII;
    fresh.sizeMiB = 1;
    fresh.generate();
    fresh.build();
    assertTrue(b.randomEdits(fresh).length() > 0);
    fresh.build();
    int before = fresh.storage.length();
    assertEquals(before + 2 * Workloads.W5_MATCHES, b.replaceAll(fresh).length());
    EditBenchmark.Edited edited = new EditBenchmark.Edited();
    edited.corpus = Corpus.CRLF;
    edited.sizeMiB = 1;
    edited.build();
    String afterW3 = edited.storage.snapshot().toString();
    assertEquals(afterW3, b.undoRedo(edited).snapshot().toString());
  }

  @Test
  void readBenchmark() {
    ReadBenchmark b = new ReadBenchmark();
    ReadBenchmark.Lookups lookups = new ReadBenchmark.Lookups();
    lookups.corpus = Corpus.MIXED_EOL;
    lookups.sizeMiB = 1;
    lookups.build();
    assertTrue(b.lineAt(lookups) > 0);
    assertTrue(b.offsetOfLine(lookups) > 0);
    assertTrue(b.lineView(lookups) > 0);
    b.line(lookups, blackhole());
    ReadBenchmark.Whole whole = new ReadBenchmark.Whole();
    whole.corpus = Corpus.ASCII;
    whole.sizeMiB = 1;
    whole.build();
    long sum = b.charAt(whole);
    assertEquals(sum, b.cursor(whole));
    assertEquals(sum, b.chunks(whole));
  }

  @Test
  void projectionBenchmark() {
    ProjectionBenchmark b = new ProjectionBenchmark();
    int divisor = 1000;
    for (ProjectionBenchmark.Shape shape : ProjectionBenchmark.Shape.values()) {
      ProjectionBenchmark.Typing typing = new ProjectionBenchmark.Typing();
      typing.shape = shape;
      typing.divisor = divisor;
      typing.open();
      try {
        int rows = typing.projected.projection.rowCount();
        assertEquals(rows, b.edit(typing));
      } finally {
        typing.close();
      }
    }
    ProjectionBenchmark.Built built = new ProjectionBenchmark.Built();
    built.shape = ProjectionBenchmark.Shape.LINES_WRAP;
    built.divisor = divisor;
    built.open();
    try {
      assertTrue(b.build(built) != null);
    } finally {
      built.close();
    }
    ProjectionBenchmark.Wrapped wrapped = new ProjectionBenchmark.Wrapped();
    wrapped.shape = ProjectionBenchmark.Shape.LONGLINE_WRAP;
    wrapped.divisor = divisor;
    wrapped.open();
    try {
      assertTrue(b.rewrap(wrapped) > 1);
    } finally {
      wrapped.close();
    }
    ProjectionBenchmark.Folds folds = new ProjectionBenchmark.Folds();
    folds.shape = ProjectionBenchmark.Shape.LINES_FOLDS;
    folds.divisor = divisor;
    folds.open();
    try {
      int rows = folds.projected.projection.rowCount();
      assertEquals(rows, b.foldAndUnfold(folds));
    } finally {
      folds.close();
    }
    ProjectionBenchmark.Expanded expanded = new ProjectionBenchmark.Expanded();
    expanded.divisor = divisor;
    expanded.open();
    try {
      assertEquals(1000, b.foldAllAndUnfoldAll(expanded));
    } finally {
      expanded.close();
    }
    ProjectionBenchmark.Lookups lookups = new ProjectionBenchmark.Lookups();
    lookups.shape = ProjectionBenchmark.Shape.LINES_FOLDS;
    lookups.divisor = divisor;
    lookups.open();
    try {
      assertTrue(b.toDisplay(lookups) > 0);
      assertTrue(b.toModel(lookups) > 0);
    } finally {
      lookups.close();
    }
    ProjectionBenchmark.Breaking breaking = new ProjectionBenchmark.Breaking();
    breaking.shape = ProjectionBenchmark.Shape.LINES_WRAP;
    breaking.divisor = divisor;
    breaking.open();
    try {
      int rows = breaking.projected.projection.rowCount();
      assertEquals(rows, b.editLineBreak(breaking));
    } finally {
      breaking.close();
    }
  }

  @Test
  void searchBenchmark() throws Exception {
    SearchBenchmark b = new SearchBenchmark();
    SearchBenchmark.Tree tree = new SearchBenchmark.Tree();
    tree.query = SearchTree.Query.LITERAL_FEW;
    tree.smallFiles = 250;
    tree.largeChars = 100_000;
    tree.open();
    Path root = tree.tree.root();
    try {
      assertTrue(b.fileSearch(tree) > 0);
      assertTrue(b.hyperSearch(tree) > 0);
    } finally {
      tree.close();
    }
    assertFalse(Files.exists(root));
  }

  @Test
  void multiCaretBenchmark() {
    MultiCaretBenchmark b = new MultiCaretBenchmark();
    MultiCaretBenchmark.Carets carets = new MultiCaretBenchmark.Carets();
    carets.carets = 100;
    carets.wrap = true;
    carets.size = 100_000;
    carets.open();
    try {
      assertTrue(b.keystroke(carets) > 0);
      assertEquals(100_100, carets.document.text().length());
    } finally {
      carets.close();
    }
  }

  @Test
  void undoBenchmark() {
    UndoBenchmark b = new UndoBenchmark();
    for (UndoBenchmark.Step step : UndoBenchmark.Step.values()) {
      UndoBenchmark.Undoable undoable = new UndoBenchmark.Undoable();
      undoable.step = step;
      undoable.lines = 10_000;
      undoable.open();
      try {
        String after = undoable.document.text().toString();
        assertTrue(b.undoAndRedo(undoable) > 0);
        assertEquals(after, undoable.document.text().toString());
      } finally {
        undoable.close();
      }
    }
  }

  @Test
  void highlightBenchmark() {
    HighlightBenchmark b = new HighlightBenchmark();
    HighlightBenchmark.Highlighted highlighted = new HighlightBenchmark.Highlighted();
    highlighted.lines = 2_000;
    highlighted.open();
    try {
      int lines = highlighted.document.text().lineCount();
      assertEquals(lines, b.typeChar(highlighted));
      assertEquals(lines, b.openComment(highlighted));
      assertTrue(b.installRegions(highlighted) > 0);
    } finally {
      highlighted.close();
    }
  }

  @Test
  void workspaceBenchmark() throws IOException {
    WorkspaceBenchmark b = new WorkspaceBenchmark();
    WorkspaceBenchmark.Tree tree = new WorkspaceBenchmark.Tree();
    tree.modules = 1;
    tree.flatFiles = 100;
    tree.create();
    Path root = tree.root;
    try {
      assertEquals(1000, b.indexBuild(tree));
      assertEquals(100, b.listFlat(tree));
      assertTrue(b.matchIndex(tree) >= b.matchSync(tree));
    } finally {
      tree.delete();
    }
    assertFalse(Files.exists(root));
  }
}
