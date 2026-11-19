package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The results dock's Copy and Copy All Results through {@link SearchController}: the lines of a
 * match, a group and a set, every set newest first, paths with spaces and non-ASCII names, line
 * numbers after an edit, and a 100,000-match set built on a worker under the busy cursor or refused
 * by the memory budget.
 */
class ResultsCopyTest {
  @TempDir Path temp;

  private Harness harness;
  private SearchController search;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    search = harness.application.search();
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().closeAll(null);
    while (harness.view.unsaved != null) {
      harness.view.unsaved.accept(UnsavedChoice.DISCARD);
      harness.view.unsaved = null;
      harness.ui.runPending();
    }
    harness.close();
  }

  private void run(String text, SearchScope scope, String directory) throws Exception {
    search.run(SearchControllerTest.form(text, scope, directory));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "search ends");
    harness.ui.runPending();
  }

  /** Returns a group's index in the newest set by its file name. */
  private int group(int set, String name) {
    for (int f = 0; f < search.fileCount(set); f++) {
      if (search.group(set, f).name().equals(name)) {
        return f;
      }
    }
    throw new AssertionError(name);
  }

  /** The lines a group copies, built from the group's own path. */
  private List<String> lines(int set, int file, String... matches) {
    FileResult f = search.group(set, file);
    List<String> lines = new ArrayList<String>();
    for (String match : matches) {
      lines.add(f.path() + ":" + match);
    }
    return lines;
  }

  private static String join(List<String> lines) {
    return String.join(System.lineSeparator(), lines);
  }

  @Test
  void copyGivesPathLineAndTextForAMatchAGroupAndASet() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("sub dir"));
    Path spaced =
        Files.writeString(
            tree.resolve("a b.txt"), "key one\nnone\n  key two\n", StandardCharsets.UTF_8);
    Path accented =
        Files.writeString(tree.resolve("naïve-ü.txt"), "ü key\n", StandardCharsets.UTF_8);
    run("key", SearchScope.TREE, tree.toString());
    int a = group(0, "a b.txt");
    int u = group(0, "naïve-ü.txt");
    assertTrue(Files.isSameFile(spaced, search.group(0, a).path()));
    assertTrue(Files.isSameFile(accented, search.group(0, u).path()));

    search.copy(0, a, 1);
    assertEquals(join(lines(0, a, "3: key two")), harness.view.clipboard);
    search.copy(0, a, -1);
    assertEquals(join(lines(0, a, "1: key one", "3: key two")), harness.view.clipboard);
    search.copy(0, -1, -1);
    List<String> set = new ArrayList<String>();
    for (int f = 0; f < search.fileCount(0); f++) {
      set.addAll(f == a ? lines(0, a, "1: key one", "3: key two") : lines(0, u, "1: ü key"));
    }
    assertEquals(join(set), harness.view.clipboard);
    assertTrue(harness.view.clipboard.contains("naïve-ü.txt:1: ü key"));

    run("two", SearchScope.TREE, tree.toString());
    search.copyAll();
    List<String> all = new ArrayList<String>(lines(0, 0, "3: key two"));
    all.addAll(set);
    assertEquals(join(all), harness.view.clipboard);
  }

  @Test
  void aDocumentGroupCopiesItsCurrentLinesAndAnUntitledOneItsTabName() throws Exception {
    harness.open(
        Files.writeString(temp.resolve("doc.txt"), "key\nother\nkey\n", StandardCharsets.UTF_8));
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    EditorController editor = harness.application.documents().activeEditor();
    editor.type("added\n");
    search.copy(0, 0, 1);
    assertEquals(lines(0, 0, "4: key").get(0), harness.view.clipboard);

    harness.application.documents().newDocument();
    harness.application.documents().activeEditor().type("one key");
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    assertNull(search.group(0, 0).path());
    search.copy(0, 0, 0);
    assertEquals(search.group(0, 0).name() + ":1: one key", harness.view.clipboard);
  }

  @Test
  void aLargeSetCopiesOnAWorkerUnderTheBusyCursorOrNotAtAllWithoutMemory() throws Exception {
    final int count = 100_000;
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append("key ").append(i).append('\n');
    }
    harness.open(
        Files.writeString(temp.resolve("large.txt"), text.toString(), StandardCharsets.UTF_8));
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    assertEquals(count, search.matchCount(0, 0));
    assertTrue(count > ResultsCopy.INLINE_MATCHES);
    final String path = search.group(0, 0).path().toString();

    MemoryBudget budget = harness.application.windowSet().budget();
    long held = budget.available() - 1000;
    assertTrue(budget.reserve(held));
    try {
      search.copy(0, -1, -1);
      assertEquals(
          UiText.RESULTS_COPY_NO_MEMORY.format(count),
          harness.view.notices.get(harness.view.notices.size() - 1));
      assertNull(harness.view.clipboard);
    } finally {
      budget.release(held);
    }

    long reserved = budget.reserved();
    int before = harness.view.activations.get();
    search.copy(0, -1, -1);
    assertNull(harness.view.clipboard);
    assertEquals(before + 1, harness.view.activations.get());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.clipboard != null;
          }
        },
        "the copy ends");
    harness.awaitIdle("copy");
    String[] copied = harness.view.clipboard.split(System.lineSeparator(), -1);
    assertEquals(count, copied.length);
    assertEquals(path + ":1: key 0", copied[0]);
    assertEquals(path + ":" + count + ": key " + (count - 1), copied[count - 1]);
    assertEquals(reserved, budget.reserved());
  }
}
