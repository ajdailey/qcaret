package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.view.PreviewLine;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replace through the controllers against a fake view, with real workers: one undo step per open
 * document, a document changed since the search skipped, a tree replace with unchecked matches and
 * files, read-only and changed files skipped, an open file edited as its buffer, cancel between
 * files, Restore Replace Run refusing a file changed since, and Rerun File on a stale group.
 */
class ReplaceControllerTest {
  @TempDir Path temp;

  private Harness harness;
  private SearchController search;
  private ReplaceController replace;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    search = harness.application.search();
    replace = harness.application.replace();
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private void run(String text, SearchScope scope, String directory) throws Exception {
    run(text, false, scope, directory);
  }

  private void run(String text, boolean regex, SearchScope scope, String directory)
      throws Exception {
    search.run(
        new SearchForm(
            text, regex, true, false, scope, directory, "", "", false, "", List.of(), List.of(),
            List.of()));
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

  private EditorController open(Path file) throws Exception {
    harness.open(file);
    return harness.application.documents().activeEditor();
  }

  /** Opens the preview of the newest set with a replacement and waits for its first view. */
  private ReplaceInput preview(String with) throws Exception {
    search.replace(0);
    final ReplaceInput input = harness.view.replacePreview;
    input.setReplacement(with);
    awaitPreview(input);
    return input;
  }

  private void awaitPreview(final ReplaceInput input) throws Exception {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return input.preview() != null;
          }
        },
        "preview computed");
  }

  /** Applies and waits for the newest report to end. */
  private ReportInput apply(ReplaceInput input) throws Exception {
    int before = harness.view.reports.size();
    assertTrue(input.apply());
    assertEquals(before + 1, harness.view.reports.size());
    return awaitReport(harness.view.reports.get(before));
  }

  private ReportInput awaitReport(final ReportInput report) throws Exception {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !report.running();
          }
        },
        "report ends");
    harness.ui.runPending();
    return report;
  }

  private static List<String> results(ReportInput report) {
    List<String> rows = new ArrayList<String>();
    for (int i = 0; i < report.rowCount(); i++) {
      rows.add(report.row(i).file() + ": " + report.row(i).result());
    }
    return rows;
  }

  private static String skipped(UiText reason) {
    return UiText.REPLACE_ROW_SKIPPED.format(reason.text());
  }

  @Test
  void aSelectionReplacesAsOneUndoStepAndStaysDirty() throws Exception {
    EditorController editor =
        open(Files.writeString(temp.resolve("doc.txt"), "key one\nkey two\nkey three\n"));
    editor.selections().set(SelectionSet.of(List.of(Selection.of(8, 26)), 0));
    run("key", SearchScope.SELECTION, "");
    ReplaceInput input = preview("lock");
    assertEquals(1, input.fileCount());
    assertEquals(2, input.matchCount(0));
    List<PreviewLine> lines = input.preview();
    assertTrue(
        lines.contains(new PreviewLine(PreviewLine.Kind.REMOVED, 2, "key two")), lines.toString());
    assertTrue(
        lines.contains(new PreviewLine(PreviewLine.Kind.ADDED, 3, "lock three")), lines.toString());
    ReportInput report = apply(input);
    assertEquals(List.of("doc.txt: " + UiText.REPLACE_ROW_CHANGED.format(2)), results(report));
    assertEquals("key one\nlock two\nlock three\n", editor.document().text().toString());
    assertTrue(editor.document().isDirty());
    editor.undo();
    assertEquals("key one\nkey two\nkey three\n", editor.document().text().toString());
  }

  @Test
  void openDocumentsReplaceEachAndSkipOneChangedSinceTheSearch() throws Exception {
    EditorController first = open(Files.writeString(temp.resolve("a.txt"), "key\n"));
    EditorController second = open(Files.writeString(temp.resolve("b.txt"), "key key\n"));
    run("key", true, SearchScope.OPEN_DOCUMENTS, "");
    ReplaceInput input = preview("x$0");
    assertEquals(UiText.REPLACE_SUMMARY.format(3, "3 matches", 2, "2 files"), input.summary());
    input.setReplacement("x\\");
    assertFalse(input.canApply());
    input.setReplacement("lock");
    first.selections().set(SelectionSet.caret(0));
    first.type("edit ");
    ReportInput report = apply(input);
    assertEquals(
        List.of(
            "a.txt: " + skipped(UiText.REASON_CHANGED),
            "b.txt: " + UiText.REPLACE_ROW_CHANGED.format(2)),
        results(report));
    assertEquals("edit key\n", first.document().text().toString());
    assertEquals("lock lock\n", second.document().text().toString());
    assertFalse(report.canRestore());
  }

  @Test
  void aTreeReplaceWritesCheckedMatchesSkipsAndRestores() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path crlf = Files.writeString(tree.resolve("crlf.txt"), "key 1\r\nkey 2\r\n");
    Path changed = Files.writeString(tree.resolve("changed.txt"), "key\n");
    Path locked = Files.writeString(tree.resolve("locked.txt"), "key\n");
    Path unchecked = Files.writeString(tree.resolve("unchecked.txt"), "key\n");
    Path open = Files.writeString(tree.resolve("open.txt"), "key\n");
    run("key", true, SearchScope.TREE, tree.toString());
    EditorController editor = open(open);
    ReplaceInput input = preview("lo\\nck");
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < input.fileCount(); i++) {
      names.add(input.file(i).substring(0, input.file(i).indexOf(' ')));
    }
    assertEquals(
        List.of("changed.txt", "crlf.txt", "locked.txt", "open.txt", "unchecked.txt"), names);
    int crlfAt = names.indexOf("crlf.txt");
    input.select(crlfAt);
    awaitPreview(input);
    assertTrue(
        input.preview().contains(new PreviewLine(PreviewLine.Kind.ADDED, 2, "ck 1")),
        input.preview().toString());
    input.setMatchChecked(crlfAt, 1, false);
    input.setFileChecked(names.indexOf("unchecked.txt"), false);
    Files.writeString(changed, "key changed\n");
    readOnly(locked, true);
    try {
      boolean lockedWritable = Files.isWritable(locked);
      ReportInput report = apply(input);
      List<String> rows = results(report);
      assertEquals("changed.txt: " + skipped(UiText.REASON_CHANGED), rows.get(0));
      assertEquals("crlf.txt: " + UiText.REPLACE_ROW_CHANGED.format(1), rows.get(1));
      if (!lockedWritable) {
        assertEquals("locked.txt: " + skipped(UiText.REASON_READ_ONLY), rows.get(2));
        assertEquals("key\n", Files.readString(locked));
      }
      assertEquals("open.txt: " + UiText.REPLACE_ROW_CHANGED.format(1), rows.get(3));
      assertEquals(4, rows.size());
      assertEquals("lo\r\nck 1\r\nkey 2\r\n", Files.readString(crlf));
      assertEquals("key\n", Files.readString(unchecked));
      assertEquals("key\n", Files.readString(open));
      assertEquals("lo\nck\n", editor.document().text().toString());
      assertTrue(search.file(0, names.indexOf("crlf.txt")).stale());
      assertTrue(report.canRestore());
      Files.writeString(crlf, "edited after the replace\n");
      report.restore();
      assertFalse(report.canRestore());
      ReportInput restore = awaitReport(harness.view.reports.get(1));
      assertTrue(
          results(restore).contains("crlf.txt: " + skipped(UiText.REASON_CHANGED_SINCE)),
          results(restore).toString());
      assertEquals("edited after the replace\n", Files.readString(crlf));
    } finally {
      readOnly(locked, false);
    }
  }

  @Test
  void restoreReplaceRunPrunesExactlyTheRunsOlderThanTheBackupDaysByItsClock() throws Exception {
    Path root = temp.resolve("runs");
    Instant now = Instant.parse("2026-11-05T10:00:00Z");
    Duration days = Duration.ofDays(ReplaceBackups.DEFAULT_DAYS);
    ReplaceBackups.start(root, now.minus(days).minus(Duration.ofHours(1)));
    ReplaceBackups kept = ReplaceBackups.start(root, now.minus(days).plus(Duration.ofHours(1)));
    ReplaceController fixed =
        new ReplaceController(
            harness.application.documents(),
            harness.workers,
            harness.ui,
            harness.view,
            new MemoryBudget(1L << 30, 60),
            HyperSearch.Limits.DEFAULTS,
            root,
            ReplaceBackups.DEFAULT_DAYS,
            Clock.fixed(now, ZoneOffset.UTC));
    fixed.chooseRun();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.runChooser != null;
          }
        },
        "runs listed");
    assertEquals(1, harness.view.runChoices.size());
    List<ReplaceBackups.Run> left = ReplaceBackups.list(root);
    assertEquals(1, left.size());
    assertEquals(kept.id(), left.get(0).id());
  }

  @Test
  void restoreReplaceRunListsRunsAndWritesTheOriginalsBack() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path one = Files.writeString(tree.resolve("one.txt"), "key one\n");
    Path two = Files.writeString(tree.resolve("two.txt"), "key two\n", StandardCharsets.UTF_8);
    run("key", SearchScope.DIRECTORY, tree.toString());
    apply(preview("lock"));
    assertEquals("lock one\n", Files.readString(one));
    replace.chooseRun();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.runChooser != null;
          }
        },
        "runs listed");
    assertEquals(1, harness.view.runChoices.size());
    assertTrue(
        harness.view.runChoices.get(0).endsWith(UiText.RESTORE_RUN_ROW.format("", "2 files")),
        harness.view.runChoices.toString());
    harness.view.runChooser.accept(0);
    ReportInput restore = awaitReport(harness.view.reports.get(1));
    List<String> rows = results(restore);
    rows.sort(null);
    assertEquals(
        List.of(
            "one.txt: " + UiText.REPLACE_ROW_RESTORED.text(),
            "two.txt: " + UiText.REPLACE_ROW_RESTORED.text()),
        rows);
    assertEquals("key one\n", Files.readString(one));
    assertEquals("key two\n", Files.readString(two));
  }

  private void awaitRunChooser() throws Exception {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.runChooser != null;
          }
        },
        "runs listed");
  }

  @Test
  void restoreReportsAFileAlreadyBackAMissingFileAndADamagedBackup() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path one = Files.writeString(tree.resolve("one.txt"), "key one\n");
    Path two = Files.writeString(tree.resolve("two.txt"), "key two\n");
    Files.writeString(tree.resolve("three.txt"), "key three\n");
    run("key", SearchScope.DIRECTORY, tree.toString());
    apply(preview("lock"));
    Files.writeString(one, "key one\n");
    Files.delete(two);
    List<ReplaceBackups.Run> runs =
        ReplaceBackups.list(temp.resolve("settings").resolve(ReplaceBackups.DIRECTORY));
    assertEquals(1, runs.size());
    Path directory = runs.get(0).directory();
    for (ReplaceBackups.Entry entry : ReplaceBackups.entries(directory)) {
      if (entry.file().getFileName().toString().equals("three.txt")) {
        Files.write(
            directory.resolve(entry.number() + ".bak"),
            "damaged".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND);
      }
    }
    replace.chooseRun();
    awaitRunChooser();
    harness.view.runChooser.accept(0);
    List<String> rows = results(awaitReport(harness.view.reports.get(1)));
    rows.sort(null);
    assertEquals(
        List.of(
            "one.txt: " + skipped(UiText.REASON_UNCHANGED),
            "three.txt: " + UiText.REPLACE_ROW_FAILED.format("The backup is damaged"),
            "two.txt: " + skipped(UiText.REASON_MISSING)),
        rows);
  }

  @Test
  void restoreRefusesWhileARunRunsAndAfterDisposeAndFailsForADeletedRun() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Files.writeString(tree.resolve("one.txt"), "key one\n");
    run("key", SearchScope.DIRECTORY, tree.toString());
    apply(preview("lock"));
    Path listed =
        ReplaceBackups.list(temp.resolve("settings").resolve(ReplaceBackups.DIRECTORY))
            .get(0)
            .directory();
    replace.chooseRun();
    awaitRunChooser();
    run("lock", SearchScope.DIRECTORY, tree.toString());
    ReplaceInput input = preview("key");
    int notices = harness.view.notices.size();
    assertTrue(input.apply());
    replace.chooseRun();
    harness.view.runChooser.accept(0);
    assertEquals(
        List.of(UiText.REPLACE_RUNNING.text(), UiText.REPLACE_RUNNING.text()),
        harness.view.notices.subList(notices, harness.view.notices.size()));
    assertEquals(2, harness.view.reports.size());
    awaitReport(harness.view.reports.get(1));
    try (DirectoryStream<Path> files = Files.newDirectoryStream(listed)) {
      for (Path file : files) {
        Files.delete(file);
      }
    }
    Files.delete(listed);
    harness.view.runChooser.accept(0);
    ReportInput restore = awaitReport(harness.view.reports.get(2));
    assertEquals(1, restore.rowCount());
    assertTrue(restore.row(0).failed(), restore.row(0).result());
    replace.dispose();
    harness.view.runChooser.accept(0);
    replace.chooseRun();
    assertEquals(3, harness.view.reports.size());
  }

  @Test
  void restoreReloadsACleanOpenDocument() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path one = Files.writeString(tree.resolve("one.txt"), "key one\n");
    run("key", SearchScope.DIRECTORY, tree.toString());
    apply(preview("lock"));
    final EditorController editor = open(one);
    assertEquals("lock one\n", editor.document().text().toString());
    replace.chooseRun();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.runChooser != null;
          }
        },
        "runs listed");
    harness.view.runChooser.accept(0);
    awaitReport(harness.view.reports.get(1));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.document().text().toString().equals("key one\n");
          }
        },
        "the restored file reloads");
    assertFalse(editor.document().isDirty());
  }

  @Test
  void cancelStopsBetweenFilesAndLeavesTheRestUntouched() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("many"));
    for (int i = 0; i < 300; i++) {
      Files.writeString(tree.resolve(String.format("f%03d.txt", i)), "key " + i + "\n");
    }
    run("key", SearchScope.DIRECTORY, tree.toString());
    ReplaceInput input = preview("lock");
    assertTrue(input.apply());
    ReportInput report = harness.view.reports.get(0);
    report.cancel();
    awaitReport(report);
    int changed = 0;
    int waiting = 0;
    for (int i = 0; i < report.rowCount(); i++) {
      ReportInput.Row row = report.row(i);
      String text = Files.readString(tree.resolve(row.file()));
      if (row.result().equals(UiText.REPLACE_ROW_NOT_ATTEMPTED.text())) {
        waiting++;
        assertTrue(text.startsWith("key "), row.file());
      } else {
        changed++;
        assertEquals(UiText.REPLACE_ROW_CHANGED.format(1), row.result(), row.file());
        assertTrue(text.startsWith("lock "), row.file());
      }
    }
    assertTrue(waiting > 0, "nothing left after the cancel");
    assertEquals(300, changed + waiting);
  }

  @Test
  void rerunFileSearchesAStaleGroupAgain() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path file = Files.writeString(tree.resolve("one.txt"), "key\n");
    Files.writeString(tree.resolve("two.txt"), "key key\n");
    run("key", SearchScope.DIRECTORY, tree.toString());
    Files.writeString(file, "key key key\n");
    search.expanded(0, 0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.file(0, 0).stale();
          }
        },
        "group stale");
    search.rerunFile(0, 0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.file(0, 0).stale();
          }
        },
        "group searched again");
    assertEquals(3, search.matchCount(0, 0));
    assertEquals(5, search.sets().get(0).matches());
    Files.writeString(file, "nothing\n");
    search.expanded(0, 0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.file(0, 0).stale();
          }
        },
        "group stale again");
    search.rerunFile(0, 0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.fileCount(0) == 1;
          }
        },
        "group left the set");
    assertEquals(UiText.SEARCH_RERUN_FILE_EMPTY.format("one.txt"), last(harness.view.notices));
  }

  @Test
  void saveAsOntoAFileOpenInAnotherTabClosesThatTab() throws Exception {
    Path target = Files.writeString(temp.resolve("target.txt"), "target\n");
    EditorController held = open(target);
    held.selections().set(SelectionSet.caret(0));
    held.type("unsaved ");
    EditorController source = open(Files.writeString(temp.resolve("source.txt"), "source\n"));
    DocumentController documents = harness.application.documents();
    documents.saveAs();
    harness.view.saveChooser.accept(target);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.replaceOpen != null;
          }
        },
        "asked to replace the open file");
    assertEquals("target.txt", harness.view.replaceOpenName);
    harness.view.replaceOpen.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == 1;
          }
        },
        "the other tab closes");
    harness.awaitIdle("save");
    assertEquals("source\n", Files.readString(target));
    assertEquals(source, documents.activeEditor());
    assertEquals(target.toRealPath(), documents.activePath());
  }

  private static String last(List<String> list) {
    return list.get(list.size() - 1);
  }

  private static void readOnly(Path file, boolean on) throws IOException {
    if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
      Files.setPosixFilePermissions(
          file, PosixFilePermissions.fromString(on ? "r--r--r--" : "rw-r--r--"));
    } else {
      Files.setAttribute(file, "dos:readonly", on);
    }
  }

  @Test
  void theBackupDaysDecideWhatTheNextListingPrunes() throws Exception {
    Path tree = Files.createDirectory(temp.resolve("days"));
    Files.writeString(tree.resolve("one.txt"), "key one\n");
    run("key", SearchScope.DIRECTORY, tree.toString());
    apply(preview("lock"));
    replace.setBackupDays(0);
    replace.chooseRun();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.runChooser != null || !harness.view.infos.isEmpty();
          }
        },
        "runs listed");
    assertTrue(harness.view.runChoices == null || harness.view.runChoices.isEmpty());
  }
}
