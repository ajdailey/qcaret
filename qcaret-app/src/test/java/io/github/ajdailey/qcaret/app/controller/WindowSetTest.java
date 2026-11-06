package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
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

/** Several main windows over one {@link WindowSet}: shared state, handoffs, close and quit. */
class WindowSetTest {
  @TempDir Path temp;

  private Windows windows;
  private WindowSet set;
  private ApplicationController first;

  @BeforeEach
  void setUp() {
    windows = new Windows(temp, true);
    set = windows.set;
    first = windows.first();
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  private Path file(String name, String text) throws Exception {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  private static StartupRequest handoff(FileRequest file) {
    return StartupRequest.handoff(List.of(file), false, null, null, null, null, null, null);
  }

  @Test
  void newWindowOpensAnUntitledDocumentAndBecomesTheTarget() {
    first.execute(CommandId.NEW_WINDOW);
    assertEquals(2, set.windows().size());
    ApplicationController added = windows.controllers.get(1);
    assertEquals(1, added.documents().count());
    assertSame(added, set.target());
  }

  @Test
  void untitledNumbersRunAcrossWindows() {
    first.documents().newDocument();
    first.execute(CommandId.NEW_WINDOW);
    assertEquals(List.of("Untitled 1"), first.documents().labels());
    assertEquals(List.of("Untitled 2"), windows.controllers.get(1).documents().labels());
  }

  @Test
  void aSettingReachesEveryWindow() {
    ApplicationController second = windows.second();
    Preferences current = set.settings().current();
    set.settings().set(current.withOptions(current.options().withFontSize(19)));
    assertEquals(19, windows.view(first).fontSize);
    assertEquals(19, windows.view(second).fontSize);
  }

  @Test
  void aKeymapChangeReachesEveryWindow() {
    ApplicationController second = windows.second();
    Preferences current = set.settings().current();
    assertNull(windows.view(second).keymap);
    set.settings()
        .set(
            current.withShortcuts(
                io.github.ajdailey.qcaret.app.command.ShortcutOverrides.NONE.with(
                    CommandId.NEW, List.of())));
    assertSame(set.keymap(), windows.view(first).keymap);
    assertSame(set.keymap(), windows.view(second).keymap);
    assertSame(set.keymap(), second.keymap());
  }

  @Test
  void aClosedWindowHearsNoMoreSettings() throws Exception {
    ApplicationController second = windows.second();
    second.closeWindow();
    windows.ui.runPending();
    Preferences current = set.settings().current();
    set.settings().set(current.withOptions(current.options().withFontSize(21)));
    assertEquals(0, windows.view(second).fontSize);
    assertEquals(21, windows.view(first).fontSize);
  }

  @Test
  void aWarningMarksEveryWindowAndShowLogClearsThemAll() throws Exception {
    ApplicationController second = windows.second();
    windows.recent.append("WARN", true);
    windows.ui.runPending();
    assertTrue(windows.view(first).warningMarker);
    assertTrue(windows.view(second).warningMarker);
    second.execute(CommandId.SHOW_LOG);
    assertFalse(windows.view(first).warningMarker);
    assertFalse(windows.view(second).warningMarker);
  }

  @Test
  void recentFilesAreSharedBetweenWindows() throws Exception {
    ApplicationController second = windows.second();
    Path notes = file("notes.txt", "one\n");
    windows.open(second, notes);
    assertEquals(List.of(notes.toRealPath()), first.recentFiles().paths());
  }

  @Test
  void handedFilesGoToTheWindowActivatedLastWhichComesToTheFront() throws Exception {
    ApplicationController second = windows.second();
    first.windowActivated();
    int raised = windows.view(first).raised;
    Path notes = file("notes.txt", "one\n");
    set.openHandoff(handoff(new FileRequest(notes)));
    windows.settle("handoff");
    assertEquals(1, first.documents().count());
    assertEquals(0, second.documents().count());
    assertEquals(raised + 1, windows.view(first).raised);
  }

  @Test
  void aHandoffWithNewWindowOpensThereAtItsGeometry() throws Exception {
    Path notes = file("notes.txt", "one\n");
    set.openHandoff(
        StartupRequest.handoff(
            List.of(new FileRequest(notes)), true, 10, 20, 300, 200, null, null));
    windows.settle("handoff");
    assertEquals(2, set.windows().size());
    ApplicationController added = windows.controllers.get(1);
    assertEquals(1, added.documents().count());
    assertEquals(Integer.valueOf(10), windows.geometries.get(0).x());
    assertEquals(Integer.valueOf(200), windows.geometries.get(0).height());
  }

  @Test
  void aHandoffWithNewWindowAndNoFileOpensAnUntitledDocument() {
    set.openHandoff(StartupRequest.handoff(List.of(), true, null, null, null, null, null, null));
    assertEquals(1, windows.controllers.get(1).documents().count());
  }

  @Test
  void aHandedDuplicateSelectsTheTabThatHoldsItWhereverItIs() throws Exception {
    final ApplicationController second = windows.second();
    Path other = file("other.txt", "other\n");
    Path notes = file("notes.txt", "one\ntwo\nthree\n");
    windows.open(second, notes);
    windows.open(second, other);
    first.windowActivated();
    int raised = windows.view(second).raised;
    set.openHandoff(handoff(new FileRequest(notes, 3, 2, false, null)));
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.documents().activeIndex() == 0;
          }
        },
        "the duplicate selected");
    windows.settle("handoff");
    assertEquals(0, first.documents().count());
    assertEquals(2, second.documents().count());
    assertEquals(raised + 1, windows.view(second).raised);
    assertEquals(
        "one\ntwo\n".length() + 1,
        second.documents().activeEditor().selections().selections().primary().caret());
  }

  @Test
  void anOpenThroughALinkToAFileAnotherWindowHoldsSelectsThatTab() throws Exception {
    ApplicationController second = windows.second();
    Path notes = file("notes.txt", "one\n");
    windows.open(second, notes);
    Path link;
    try {
      link = Files.createSymbolicLink(temp.resolve("link.txt"), notes);
    } catch (java.io.IOException | UnsupportedOperationException e) {
      return;
    }
    first.documents().open(List.of(link));
    windows.settle("open");
    assertEquals(0, first.documents().count());
    assertEquals(1, second.documents().count());
  }

  @Test
  void closingAWindowLeavesTheOthersAndTheLastOneQuits() throws Exception {
    final ApplicationController second = windows.second();
    second.closeWindow();
    windows.ui.runPending();
    assertEquals(List.of(first), set.windows());
    assertTrue(windows.view(second).disposed);
    assertFalse(set.isQuitting());
    first.closeWindow();
    assertTrue(set.isQuitting());
  }

  @Test
  void closingADirtyWindowAsksThereAndCancelKeepsIt() throws Exception {
    ApplicationController second = windows.second();
    second.documents().newDocument();
    second.documents().activeEditor().type("text");
    second.closeWindow();
    FakeView view = windows.view(second);
    assertNotNull(view.unsaved);
    view.unsaved.accept(UnsavedChoice.CANCEL);
    assertEquals(2, set.windows().size());
    assertEquals(1, second.documents().count());
    second.closeWindow();
    view.unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(List.of(first), set.windows());
  }

  @Test
  void quitAsksInEveryWindowAndCancelStopsIt() throws Exception {
    ApplicationController second = windows.second();
    first.documents().newDocument();
    first.documents().activeEditor().type("a");
    second.documents().newDocument();
    second.documents().activeEditor().type("b");
    first.windowActivated();
    first.execute(CommandId.QUIT);
    assertNotNull(windows.view(first).unsaved);
    assertNull(windows.view(second).unsaved);
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertNotNull(windows.view(second).unsaved);
    assertTrue(windows.view(second).raised > 0);
    windows.view(second).unsaved.accept(UnsavedChoice.CANCEL);
    assertFalse(set.isQuitting());
    assertEquals(2, set.windows().size());
    assertEquals(1, first.documents().dirtyDocuments().size());
    second.windowActivated();
    second.execute(CommandId.QUIT);
    windows.view(second).unsaved.accept(UnsavedChoice.DISCARD);
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(set.isQuitting());
  }

  @Test
  void aDocumentOpenedDuringAQuitPromptIsAskedAboutToo() throws Exception {
    first.documents().newDocument();
    first.documents().activeEditor().type("a");
    ApplicationController second = windows.second();
    first.windowActivated();
    first.execute(CommandId.QUIT);
    second.documents().newDocument();
    second.documents().activeEditor().type("b");
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertFalse(set.isQuitting());
    assertNotNull(windows.view(second).unsaved);
    windows.view(second).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(set.isQuitting());
  }

  private Path saveOver(final ApplicationController window, Path file) throws Exception {
    window.documents().newDocument();
    window.documents().activeEditor().type("mine");
    window.documents().saveAs();
    windows.view(window).saveChooser.accept(file);
    return file;
  }

  @Test
  void saveAsOntoACleanFileInAnotherWindowClosesItsTab() throws Exception {
    Path file = Files.writeString(temp.resolve("held.txt"), "held\n");
    windows.open(first, file);
    final int tabs = first.documents().count();
    ApplicationController second = windows.second();
    saveOver(second, file);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().count() == tabs - 1;
          }
        },
        "the other window's tab closes");
    windows.settle("save");
    assertEquals("mine", Files.readString(file));
    assertNull(windows.view(second).replaceOpen);
    assertEquals(file.toRealPath(), second.documents().activePath());
  }

  @Test
  void saveAsOntoADirtyFileInAnotherWindowAsksAndADeclineWritesNothing() throws Exception {
    Path file = Files.writeString(temp.resolve("held.txt"), "held\n");
    windows.open(first, file);
    first.documents().activeEditor().type("unsaved ");
    final int tabs = first.documents().count();
    final ApplicationController second = windows.second();
    saveOver(second, file);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.view(second).replaceOpen != null;
          }
        },
        "asked");
    assertEquals("held.txt", windows.view(second).replaceOpenName);
    windows.view(second).replaceDeclined.run();
    windows.settle("declined");
    assertEquals("held\n", Files.readString(file));
    assertEquals(tabs, first.documents().count());

    second.documents().saveAs();
    windows.view(second).replaceOpen = null;
    windows.view(second).saveChooser.accept(file);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.view(second).replaceOpen != null;
          }
        },
        "asked again");
    windows.view(second).replaceOpen.run();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().count() == tabs - 1;
          }
        },
        "the other window's tab closes");
    windows.settle("save");
    assertEquals("mine", Files.readString(file));
  }

  @Test
  void folderSearchReadsTheBufferOfAFileOpenInAnotherWindow() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("tree"));
    Path file = Files.writeString(folder.resolve("held.txt"), "disk\n");
    ApplicationController second = windows.second();
    windows.open(second, file);
    second.documents().activeEditor().type("buffer ");
    final SearchController search = first.search();

    search.run(SearchControllerTest.form("buffer", SearchScope.TREE, folder.toString()));

    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "search ends");
    windows.ui.runPending();
    assertEquals(1, search.fileCount(0));
    assertEquals(1, search.matchCount(0, 0));

    search.run(SearchControllerTest.form("disk", SearchScope.TREE, folder.toString()));
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "second search ends");
    windows.ui.runPending();
    assertEquals(1, search.fileCount(0), "the walk skips a file open in any window");

    search.open(0, 0, 0);
    assertTrue(windows.view(second).raised > 0);
    second.documents().closeActive();
    windows.view(second).unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(0, second.documents().count());
  }

  @Test
  void quitRecordsTheWindowActivatedLast() {
    ApplicationController second = windows.second();
    windows.view(second).bounds =
        new io.github.ajdailey.qcaret.app.view.WindowGeometry.Request(5, 6, 700, 500);
    windows.view(first).bounds =
        new io.github.ajdailey.qcaret.app.view.WindowGeometry.Request(1, 2, 300, 200);
    second.windowActivated();
    set.quit();
    assertEquals(700, set.settings().current().window().width());
  }

  @Test
  void quitStopsEveryWindowOnceAndAHandoffAfterItIsDropped() throws Exception {
    ApplicationController second = windows.second();
    Path notes = file("notes.txt", "one\n");
    set.quit();
    set.quit();
    set.openHandoff(handoff(new FileRequest(notes)));
    assertTrue(first.isQuitting());
    assertTrue(second.isQuitting());
    assertEquals(0, first.documents().count());
    assertEquals(0, second.documents().count());
  }

  @Test
  void readOnlyEncodingAndPositionApplyToAFileReadNow() throws Exception {
    Path notes = temp.resolve("latin.txt");
    Files.write(notes, "café\nline two\n".getBytes(StandardCharsets.ISO_8859_1));
    set.openHandoff(handoff(new FileRequest(notes, 2, 6, true, "ISO-8859-1")));
    windows.settle("handoff");
    EditorController editor = first.documents().activeEditor();
    assertNotNull(editor);
    Document document = editor.document();
    assertEquals("ISO-8859-1", document.encoding().charset().name());
    assertTrue(document.isReadOnly());
    assertEquals("café\nline ".length(), editor.selections().selections().primary().caret());
  }

  @Test
  void aMissingFileOpensEmptyWithItsOptions() throws Exception {
    Path missing = temp.resolve("missing.txt");
    set.openHandoff(handoff(new FileRequest(missing, 9, 0, true, null)));
    windows.settle("handoff");
    assertEquals(1, first.documents().count());
    assertTrue(first.documents().activeDocument().isReadOnly());
  }

  @Test
  void aDiffRequestOpensTheComparison() throws Exception {
    Path left = file("left.txt", "one\ntwo\n");
    Path right = file("right.txt", "one\nthree\n");
    final FakeView view = windows.view(first);
    set.openHandoff(StartupRequest.handoff(List.of(), false, null, null, null, null, left, right));
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.comparisons.isEmpty();
          }
        },
        "comparison");
    assertEquals(1, view.comparisons.size());
  }

  @Test
  void aDiffOfAMissingFileShowsANotice() throws Exception {
    Path right = file("right.txt", "one\n");
    final FakeView view = windows.view(first);
    set.openHandoff(
        StartupRequest.handoff(
            List.of(), false, null, null, null, null, temp.resolve("gone.txt"), right));
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.notices.isEmpty();
          }
        },
        "notice");
    assertTrue(view.comparisons.isEmpty());
  }
}
