package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file commands of the File and Encoding menus and the window title: Reload from Disk, Recently
 * Closed, Automatic Detection, Tab Width > Other, the tab defaults, and Esc stopping a line
 * transform or a comparison.
 */
class FileCommandsTest {
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

  private DocumentController documents() {
    return harness.application.documents();
  }

  private Document active() {
    return documents().activeDocument();
  }

  private Path file(String name, String text) throws Exception {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  private void awaitText(final String text) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return active() != null && text.equals(active().text().toString());
          }
        },
        "text " + text);
    harness.awaitIdle("text");
  }

  @Test
  void reloadReadsACleanDocumentWithoutAsking() throws Exception {
    Path file = file("a.txt", "one\n");
    harness.open(file);
    assertTrue(states.enabled(CommandId.RELOAD));
    Files.writeString(file, "two\n");
    harness.application.execute(CommandId.RELOAD);
    awaitText("two\n");
    assertNull(harness.view.reloadName);
    assertFalse(active().isDirty());
  }

  @Test
  void reloadAsksFirstOnADirtyDocumentAndUndoReturnsItsText() throws Exception {
    Path file = file("a.txt", "one\n");
    harness.open(file);
    documents().activeEditor().type("x");
    harness.application.execute(CommandId.RELOAD);
    assertEquals("a.txt", harness.view.reloadName);
    harness.ui.runPending();
    assertEquals("xone\n", active().text().toString());
    harness.view.reloadDecline.run();
    assertEquals("xone\n", active().text().toString());
    assertTrue(active().isDirty());

    harness.application.execute(CommandId.RELOAD);
    harness.view.reloadAccept.run();
    awaitText("one\n");
    assertFalse(active().isDirty());
    documents().activeEditor().undo();
    assertEquals("xone\n", active().text().toString());
    assertTrue(active().isDirty());
  }

  @Test
  void reloadOfAnUntitledDocumentIsDisabled() throws Exception {
    documents().newDocument();
    assertFalse(states.enabled(CommandId.RELOAD));
  }

  @Test
  void reloadOfADeletedFileShowsItsNotice() throws Exception {
    Path file = file("a.txt", "one\n");
    harness.open(file);
    Files.delete(file);
    documents().checkFiles();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return active().isDirty();
          }
        },
        "the file missed");
    harness.view.notices.clear();
    harness.application.execute(CommandId.RELOAD);
    assertEquals(List.of(UiText.CHANGE_MISSING.format("a.txt")), harness.view.notices);
    assertNull(harness.view.reloadName);
  }

  @Test
  void recentlyClosedListsTheClosedFilesAndOpensAnyOfThem() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Path c = file("c.txt", "c\n");
    harness.open(a);
    harness.open(b);
    harness.open(c);
    assertFalse(states.enabled(CommandId.CLEAR_CLOSED_FILES));
    harness.application.execute(CommandId.CLOSE_ALL);
    assertEquals(3, states.closedFiles().size());
    assertTrue(states.enabled(CommandId.CLEAR_CLOSED_FILES));

    String middle = states.closedFiles().get(1);
    harness.application.openClosed(1);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 2;
          }
        },
        "the file open");
    harness.awaitIdle("open");
    assertEquals(middle, documents().activePath().toString());
    assertEquals(2, states.closedFiles().size());

    harness.application.execute(CommandId.CLEAR_CLOSED_FILES);
    assertTrue(states.closedFiles().isEmpty());
    assertFalse(states.enabled(CommandId.REOPEN_CLOSED));
    harness.application.openClosed(0);
    harness.awaitIdle("no entry");
    assertEquals(2, documents().count());
  }

  private void reopen(final Runnable command, final String text) throws InterruptedException {
    command.run();
    awaitText(text);
  }

  @Test
  void automaticDetectionForgetsAForcedAndARememberedCharset() throws Exception {
    Path file = file("a.txt", "café\n");
    harness.open(file);
    reopen(
        new Runnable() {
          @Override
          public void run() {
            harness.application.reopenWithEncoding(StandardCharsets.ISO_8859_1);
          }
        },
        "cafÃ©\n");
    assertEquals("ISO-8859-1", documents().sessionTabs(false).get(0).encoding());

    // Closed with the charset, it comes back with it: the memory holds the file's state.
    harness.application.execute(CommandId.CLOSE);
    harness.open(file);
    assertEquals(StandardCharsets.ISO_8859_1, active().encoding().charset());

    reopen(
        new Runnable() {
          @Override
          public void run() {
            harness.application.reopenWithDetection();
          }
        },
        "café\n");
    assertEquals(StandardCharsets.UTF_8, active().encoding().charset());
    assertEquals("", documents().sessionTabs(false).get(0).encoding());

    harness.application.execute(CommandId.CLOSE);
    harness.open(file);
    assertEquals(StandardCharsets.UTF_8, active().encoding().charset());
  }

  @Test
  void automaticDetectionDoesNothingForADirtyDocument() throws Exception {
    harness.open(file("a.txt", "one\n"));
    documents().activeEditor().type("x");
    harness.application.reopenWithDetection();
    harness.awaitIdle("no load");
    assertEquals("xone\n", active().text().toString());
  }

  @Test
  void tabWidthOtherSetsAnyWidthAndChecksItself() throws Exception {
    harness.open(file("a.txt", "\tone\n"));
    assertFalse(states.checked(CommandId.TAB_WIDTH_OTHER));
    assertTrue(states.checked(CommandId.TAB_WIDTH_4));
    harness.application.execute(CommandId.TAB_WIDTH_OTHER);
    assertEquals(4, harness.view.tabWidthAsked);
    harness.view.tabWidthAnswer.accept(Document.MAX_TAB_WIDTH);
    assertEquals(Document.MAX_TAB_WIDTH, active().tabWidth());
    assertTrue(states.checked(CommandId.TAB_WIDTH_OTHER));
    assertFalse(states.checked(CommandId.TAB_WIDTH_4));
  }

  private void setTabDefaults(int width, boolean spaces) {
    Preferences current = harness.application.preferences();
    Options o = current.options();
    harness
        .application
        .settings()
        .set(
            current.withOptions(
                new Options(
                    o.completionChars(),
                    o.autoClose(),
                    o.maxHighlightLineChars(),
                    o.fontSize(),
                    o.pollSeconds(),
                    o.autoReloadClean(),
                    o.backupDays(),
                    o.searchMaxLineChars(),
                    o.excludedDirectories(),
                    o.recoveryIntervalSeconds(),
                    o.recoveryKeepDays(),
                    o.sessionSnapshot(),
                    width,
                    spaces,
                    Options.DEFAULTS.rightMargin(),
                    Options.DEFAULTS.dateFormat(),
                    Options.DEFAULTS.editorConfig(),
                    Options.DEFAULTS.modelines(),
                    false,
                    false)));
  }

  @Test
  void theTabDefaultsReachNewDocumentsAndLeaveOpenOnesAndSavedStateAlone() throws Exception {
    Path known = file("known.txt", "one\n");
    harness.open(known);
    documents().setTabWidth(3);
    harness.application.execute(CommandId.CLOSE);
    documents().newDocument();
    Document before = active();

    setTabDefaults(7, true);
    assertEquals(Document.DEFAULT_TAB_WIDTH, before.tabWidth());
    documents().newDocument();
    assertEquals(7, active().tabWidth());
    assertTrue(active().insertSpaces());

    harness.open(file("fresh.txt", "two\n"));
    assertEquals(7, active().tabWidth());
    assertTrue(active().insertSpaces());

    harness.open(known);
    assertEquals(3, active().tabWidth());
    assertFalse(active().insertSpaces());
  }

  @Test
  void theWindowTitleShowsTheNameTheDirtyMarkAndThePath() throws Exception {
    assertEquals(UiText.WINDOW_TITLE.text(), states.windowTitle());
    Path file = file("a.txt", "one\n");
    harness.open(file);
    Path real = documents().activePath();
    assertEquals(UiText.WINDOW_TITLE_FILE.format("a.txt", real), states.windowTitle());
    documents().activeEditor().type("x");
    assertEquals(UiText.WINDOW_TITLE_FILE.format("*a.txt", real), states.windowTitle());
    documents().newDocument();
    assertEquals(
        UiText.WINDOW_TITLE_DOCUMENT.format(documents().activeName()), states.windowTitle());
  }

  @Test
  void droppedFilesOpenATabEachAndAnOpenFileSelectsItsTab() throws Exception {
    final Path a = file("a.txt", "a\n");
    final Path b = file("b.txt", "b\n");
    harness.open(a);
    documents().newDocument();
    harness.application.openDropped(List.of(a, b));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 3 && documents().loading() == 0;
          }
        },
        "the dropped file");
    harness.awaitIdle("drop");
    assertNotNull(documents().documentAt(b.toRealPath()));
  }

  /** Regression: a dropped folder reached the open, which reported it as not a regular file. */
  @Test
  void aDropOpensItsFilesAndIgnoresItsFolders() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("folder"));
    final Path a = file("a.txt", "a\n");
    harness.application.openDropped(List.of(folder, a, temp));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 1 && documents().loading() == 0;
          }
        },
        "the dropped file");
    harness.awaitIdle("drop");
    assertNotNull(documents().documentAt(a.toRealPath()));
    assertEquals(List.of(), harness.view.infos);
  }

  @Test
  void escStopsALineTransform() throws Exception {
    harness.open(file("a.txt", "b\na\n"));
    documents().activeEditor().selectAll();
    harness.application.execute(CommandId.SORT_LINES_ASCENDING);
    documents().activeEditor().cancel();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<Boolean> busy = harness.view.selected.shown.busy;
            return busy.size() == 2 && !busy.get(1);
          }
        },
        "the busy cursor cleared");
    assertEquals("b\na\n", active().text().toString());
  }

  @Test
  void escStopsAComparison() throws Exception {
    harness.open(file("a.txt", "a\n"));
    harness.application.execute(CommandId.COMPARE_WITH_DISK);
    documents().activeEditor().cancel();
    harness.awaitIdle("comparison");
    assertTrue(harness.view.comparisons.isEmpty());
    harness.application.execute(CommandId.COMPARE_WITH_DISK);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.view.comparisons.isEmpty();
          }
        },
        "a later comparison");
  }
}
