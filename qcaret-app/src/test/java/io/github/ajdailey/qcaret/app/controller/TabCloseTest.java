package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tab shown after a close: the tab after the closed one, else the one before, or with {@code
 * tabs.closeShowsLastUsed} on the most recently used one; and bulk closes that show no tab on the
 * way.
 */
class TabCloseTest {
  @TempDir Path temp;

  private Windows windows;
  private ApplicationController first;
  private int files;

  @BeforeEach
  void setUp() {
    windows = new Windows(temp, false);
    first = windows.first();
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  /** Opens {@code count} new files in a window; returns them in tab order. */
  private List<Document> openTabs(ApplicationController window, int count) throws Exception {
    List<Document> opened = new ArrayList<Document>();
    for (int i = 0; i < count; i++) {
      files++;
      Path file =
          Files.writeString(
              temp.resolve("t" + files + ".txt"), "t" + files, StandardCharsets.UTF_8);
      windows.open(window, file);
      opened.add(window.documents().activeDocument());
    }
    return opened;
  }

  /** Records each change of the document shown in the first window from now on. */
  private List<Document> recordShown() {
    final List<Document> shown = new ArrayList<Document>();
    final DocumentController documents = first.documents();
    final Document[] last = {documents.activeDocument()};
    documents.addActiveListener(
        new Runnable() {
          @Override
          public void run() {
            Document now = documents.activeDocument();
            if (now != last[0]) {
              last[0] = now;
              shown.add(now);
            }
          }
        });
    return shown;
  }

  private void show(Document document) {
    first.documents().select(first.documents().openDocuments().indexOf(document));
    assertSame(document, first.documents().activeDocument());
  }

  private void closeShown() {
    first.execute(CommandId.CLOSE);
  }

  private void assertShown(Document expected) {
    assertSame(expected, first.documents().activeDocument());
    FakeView view = windows.view(first);
    assertSame(view.tabs.get(first.documents().openDocuments().indexOf(expected)), view.selected);
  }

  private void closeShowsLastUsed(boolean on) {
    Preferences current = windows.set.settings().current();
    Options o = current.options();
    windows
        .set
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
                    o.tabWidth(),
                    o.insertSpaces(),
                    o.rightMargin(),
                    o.dateFormat(),
                    o.editorConfig(),
                    o.modelines(),
                    o.closeWindowWithLastTab(),
                    on,
                    Options.DEFAULTS.newLineEnding(),
                    Options.DEFAULTS.newEncoding())));
  }

  @Test
  void closingTheShownTabShowsTheTabAfterItElseTheOneBefore() throws Exception {
    List<Document> tabs = openTabs(first, 5);
    show(tabs.get(0));
    closeShown();
    assertShown(tabs.get(1));
    show(tabs.get(2));
    closeShown();
    assertShown(tabs.get(3));
    show(tabs.get(4));
    closeShown();
    assertShown(tabs.get(3));
  }

  @Test
  void closingATabNotShownKeepsTheShownOne() throws Exception {
    List<Document> tabs = openTabs(first, 3);
    show(tabs.get(0));
    List<Document> shown = recordShown();
    windows.view(first).tabs.get(1).input.closeRequested();
    assertShown(tabs.get(0));
    assertEquals(List.of(), shown);
  }

  @Test
  void closeAllShowsNoOtherTabOnTheWay() throws Exception {
    for (int at = 0; at < 3; at++) {
      List<Document> tabs = openTabs(first, 3);
      show(tabs.get(at));
      List<Document> shown = recordShown();
      first.execute(CommandId.CLOSE_ALL);
      assertEquals(1, first.documents().count());
      assertFalse(tabs.contains(first.documents().activeDocument()));
      for (Document document : shown) {
        assertFalse(tabs.contains(document), "shown tab " + at + ": " + shown);
      }
      first.documents().closeAll(null);
    }
  }

  @Test
  void closeOthersFromATabNotShownShowsOnlyThatTab() throws Exception {
    List<Document> tabs = openTabs(first, 4);
    show(tabs.get(0));
    List<Document> shown = recordShown();
    windows.view(first).tabs.get(2).input.closeOthersRequested();
    assertEquals(List.of(tabs.get(2)), first.documents().openDocuments());
    assertEquals(List.of(tabs.get(2)), shown);
  }

  @Test
  void closeToTheLeftOrRightShowsOnlyTheKeptTab() throws Exception {
    for (int side = 0; side < 2; side++) {
      boolean left = side == 0;
      for (int at = 0; at < 5; at++) {
        List<Document> tabs = openTabs(first, 5);
        Document from = tabs.get(2);
        show(tabs.get(at));
        List<Document> shown = recordShown();
        windows.view(first).tabs.get(2).input.closeSideRequested(left);
        boolean closed = left ? at < 2 : at > 2;
        String what = (left ? "left" : "right") + ", shown tab " + at;
        assertEquals(closed ? List.of(from) : List.of(), shown, what);
        assertShown(closed ? from : tabs.get(at));
        assertEquals(3, first.documents().count(), what);
        first.documents().closeAll(null);
      }
    }
  }

  @Test
  void unsavedPromptsComeInTabOrderAndCancelStops() throws Exception {
    List<Document> tabs = openTabs(first, 4);
    show(tabs.get(1));
    first.documents().activeEditor().type("x");
    show(tabs.get(3));
    first.documents().activeEditor().type("y");
    show(tabs.get(0));
    first.execute(CommandId.CLOSE_ALL);
    assertEquals("t2.txt", windows.view(first).unsavedName);
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals("t4.txt", windows.view(first).unsavedName);
    windows.view(first).unsaved.accept(UnsavedChoice.CANCEL);
    assertTrue(first.documents().openDocuments().contains(tabs.get(3)));
    assertFalse(first.documents().openDocuments().contains(tabs.get(1)));
  }

  @Test
  void theLastUsedSettingShowsTheMostRecentlyUsedTab() throws Exception {
    closeShowsLastUsed(true);
    List<Document> tabs = openTabs(first, 5);
    show(tabs.get(4));
    show(tabs.get(0));
    show(tabs.get(1));
    closeShown();
    assertShown(tabs.get(0));
    closeShown();
    assertShown(tabs.get(4));
    closeShowsLastUsed(false);
    show(tabs.get(2));
    show(tabs.get(3));
    closeShown();
    assertShown(tabs.get(4));
  }

  @Test
  void theLastUsedOrderDropsAMovedTabAndCountsAReopenedOne() throws Exception {
    closeShowsLastUsed(true);
    List<Document> tabs = openTabs(first, 4);
    show(tabs.get(2));
    show(tabs.get(0));
    ApplicationController second = windows.second();
    assertNotNull(windows.set.moveDocument(first, tabs.get(0), second, END));
    windows.settle("move");
    assertShown(tabs.get(2));
    show(tabs.get(1));
    show(tabs.get(3));
    closeShown();
    assertShown(tabs.get(1));
    first.execute(CommandId.REOPEN_CLOSED);
    windows.settle("reopen");
    Document reopened = first.documents().activeDocument();
    assertEquals(3, first.documents().count());
    show(tabs.get(1));
    closeShown();
    assertShown(reopened);
  }

  private static final WindowSet.Placement END = new WindowSet.Placement(Integer.MAX_VALUE, 4, 5);
}
