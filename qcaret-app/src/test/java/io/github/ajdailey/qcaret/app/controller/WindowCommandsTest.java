package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Window menu's list, a tab moved to another window, and a command line during a quit. */
class WindowCommandsTest {
  @TempDir Path temp;

  private Windows windows;
  private WindowSet set;
  private ApplicationController first;

  @BeforeEach
  void setUp() {
    windows = new Windows(temp, false);
    set = windows.set;
    first = windows.first();
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  @Test
  void theWindowListNamesEachSelectedDocumentAndShowWindowRaisesOne() {
    first.documents().newDocument();
    ApplicationController second = windows.second();
    assertEquals(List.of("Untitled 1", UiText.WINDOW_EMPTY.text()), first.windowLabels());
    assertEquals(0, first.windowIndex());
    assertEquals(1, second.windowIndex());
    int raised = windows.view(second).raised;
    first.showWindow(1);
    assertEquals(raised + 1, windows.view(second).raised);
    first.showWindow(2);
    first.showWindow(-1);
    assertEquals(raised + 1, windows.view(second).raised);
  }

  @Test
  void aTabMovesToAnotherWindowAtItsIndex() throws Exception {
    ApplicationController second = windows.second();
    windows.open(second, file("there.txt"));
    Document moved = open(first, "moved.txt");
    first.moveTab(windows.view(first).tabs.get(0).input, second, 0);
    assertEquals(moved, second.documents().openDocuments().get(0));
    assertEquals(2, second.documents().count());
    assertEquals(0, first.documents().count());
  }

  @Test
  void aCommandLineAfterTheWindowStoppedOpensNothing() {
    set.quit();
    first.open(StartupRequest.handoff(List.of(), false, null, null, null, null, null, null), true);
    assertEquals(0, first.documents().count());
    assertTrue(first.isQuitting());
  }

  private Path file(String name) throws Exception {
    return Files.writeString(temp.resolve(name), "text\n", StandardCharsets.UTF_8);
  }

  private Document open(ApplicationController window, String name) throws Exception {
    windows.open(window, file(name));
    return window.documents().activeDocument();
  }
}
