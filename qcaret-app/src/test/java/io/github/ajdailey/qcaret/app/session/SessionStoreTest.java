package io.github.ajdailey.qcaret.app.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session file's round trip and its faults (SET-5, SET-8): a malformed, oversized or
 * unknown-version file loads as nothing and is kept as {@code .invalid} on the next save.
 */
class SessionStoreTest {
  @TempDir Path temp;

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  private static Session.Tab tab(String path) {
    return new Session.Tab(
        path,
        "",
        "",
        "ISO-8859-1",
        "java",
        false,
        2,
        true,
        List.of(new Session.View("5,9;1,1", 3, "4,12"), new Session.View("0,0", 0, "")),
        1,
        true,
        "30,70",
        "1,4",
        List.of(new Session.Comment(4, "two\nlines = mock")),
        false);
  }

  private static Session session() {
    SearchRequest request =
        new SearchRequest(
            "needle",
            true,
            false,
            true,
            SearchScope.TREE,
            Path.of("mock").toAbsolutePath(),
            "*.java",
            "*.tmp",
            ".git",
            true);
    Session.Window window =
        new Session.Window(
            new WindowState(1, 2, 300, 400, true),
            List.of(tab("/mock/a.txt"), tab("/mock/b.txt").withBuffer("b2")),
            1,
            "/mock",
            true,
            "1,3",
            List.of("/mock/x", "/mock/x/y"),
            true,
            "3,1",
            List.of(new Session.Search(request, true)),
            List.of("/mock/gone.txt"),
            Session.Display.DEFAULTS);
    return new Session(
        List.of(window),
        List.of("/mock/a.txt"),
        List.of(new Session.FileState("/mock/c.txt", "UTF-16BE", "xml", 8, false, "7,7", 2)));
  }

  @Test
  void theDisplayStateAndReadOnlyRoundTrip() {
    Session.Display display = new Session.Display(true, true, true, false, true);
    Session.Tab tab = tab("/mock/r.txt");
    Session.Window window =
        new Session.Window(
            new WindowState(null, null, null, null, false),
            List.of(
                new Session.Tab(
                    tab.path(),
                    tab.title(),
                    tab.buffer(),
                    tab.encoding(),
                    tab.language(),
                    tab.binary(),
                    tab.tabWidth(),
                    tab.insertSpaces(),
                    tab.views(),
                    tab.focused(),
                    tab.sideBySide(),
                    tab.weights(),
                    tab.bookmarks(),
                    tab.comments(),
                    true)),
            0,
            "",
            false,
            "",
            List.of(),
            false,
            "",
            List.of(),
            List.of(),
            display);
    SessionStore store = store();
    assertTrue(store.save(new Session(List.of(window), List.of(), List.of())));
    Session.Window loaded = store().load().windows().get(0);
    assertEquals(display, loaded.display());
    assertTrue(loaded.tabs().get(0).readOnly());
  }

  @Test
  void aFileWithoutTheDisplayKeysReadsAsTheDefaults() throws Exception {
    Files.writeString(temp.resolve(SessionStore.FILE), "version=1\nwindow.0.active=-1\n");
    assertEquals(Session.Display.DEFAULTS, store().load().windows().get(0).display());
  }

  @Test
  void withoutSearchesKeepsEverythingElse() {
    Session session = session();
    Session cleared = session.withoutSearches();
    assertEquals(List.of(), cleared.windows().get(0).searches());
    assertEquals(session.windows().get(0).tabs(), cleared.windows().get(0).tabs());
    assertEquals(session.recent(), cleared.recent());
    assertEquals(session.files(), cleared.files());
  }

  @Test
  void recentOnlyKeepsRecentFiles() {
    Session session = session();
    assertEquals(new Session(List.of(), session.recent(), List.of()), session.recentOnly());
  }

  @Test
  void aSessionRoundTrips() {
    SessionStore store = store();
    assertTrue(store.save(session()));
    assertEquals(session(), store().load());
  }

  @Test
  void aMissingFileLoadsEmpty() {
    assertEquals(Session.EMPTY, store().load());
  }

  @Test
  void aFileWithoutAVersionReadsAsVersionOne() throws Exception {
    Files.writeString(temp.resolve(SessionStore.FILE), "recent.0=/mock/a.txt\n");
    assertEquals(List.of("/mock/a.txt"), store().load().recent());
  }

  private void assertIgnoredAndKept(String content) throws Exception {
    Path file = temp.resolve(SessionStore.FILE);
    Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    SessionStore store = store();
    assertEquals(Session.EMPTY, store.load());
    assertTrue(store.save(Session.EMPTY));
    assertEquals(
        content,
        Files.readString(
            temp.resolve(SessionStore.FILE + ".invalid"), StandardCharsets.ISO_8859_1));
    assertEquals(Session.EMPTY, store().load());
  }

  @Test
  void aMalformedSessionIsIgnoredAndKept() throws Exception {
    assertIgnoredAndKept("version=1\nwindow.0.active=0\nwindow.0.tab.0.path=\\uZZZZ\n");
  }

  @Test
  void anOversizedSessionIsIgnoredAndKept() throws Exception {
    StringBuilder text = new StringBuilder("version=1\nwindow.0.active=0\n");
    while (text.length() <= SessionStore.MAX_BYTES) {
      text.append("window.0.tab.0.path=/mock/").append("a".repeat(1000)).append('\n');
    }
    assertIgnoredAndKept(text.toString());
  }

  @Test
  void anUnknownSessionVersionIsIgnoredAndKept() throws Exception {
    assertIgnoredAndKept("version=2\nwindow.0.active=0\nwindow.0.tab.0.path=/mock/a.txt\n");
  }

  @Test
  void badValuesFallBackAndATabWithoutAPathIsSkipped() throws Exception {
    Files.writeString(
        temp.resolve(SessionStore.FILE),
        "window.0.active=x\n"
            + "window.0.tab.0.tabWidth=99\nwindow.0.tab.0.path=/mock/a.txt\n"
            + "window.0.tab.0.view.0.selection=mock\nwindow.0.tab.0.view.0.top=-4\n"
            + "window.0.tab.1.tabWidth=4\n"
            + "window.0.tab.2.tabWidth=4\nwindow.0.tab.2.path=/mock/b.txt\n"
            + "window.0.search.0.text=a\nwindow.0.search.0.scope=NOWHERE\n");
    Session.Window window = store().load().windows().get(0);
    assertEquals(-1, window.active());
    assertEquals(2, window.tabs().size());
    Session.Tab tab = window.tabs().get(0);
    assertEquals(4, tab.tabWidth());
    assertEquals(0, tab.views().get(0).top());
    assertNull(SessionText.selections(tab.views().get(0).selections(), 10));
    assertEquals(List.of(), window.searches());
  }

  @Test
  void theLimitsHold() {
    List<Session.FileState> files = new ArrayList<Session.FileState>();
    for (int i = 0; i < SessionStore.MAX_FILES + 5; i++) {
      files.add(new Session.FileState("/mock/" + i, "", "", 4, false, "", 0));
    }
    SessionStore store = store();
    assertTrue(store.save(new Session(List.of(), List.of(), files)));
    Session read = store.load();
    assertEquals(SessionStore.MAX_FILES, read.files().size());
    assertEquals("/mock/0", read.files().get(0).path());
  }

  @Test
  void aSessionOverTheLimitIsRefusedAndTheFileStays() {
    SessionStore store = store();
    assertTrue(store.save(session()));
    List<Session.Comment> comments = new ArrayList<Session.Comment>();
    for (int i = 0; i < SessionStore.MAX_COMMENTS; i++) {
      comments.add(new Session.Comment(i, "\u4e16".repeat(1000)));
    }
    Session.Tab tab =
        new Session.Tab(
            "/mock/a.txt",
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(new Session.View("0,0", 0, "")),
            0,
            false,
            "",
            "",
            comments,
            false);
    Session.Window window =
        new Session.Window(
            WindowState.UNKNOWN,
            List.of(tab),
            0,
            "",
            false,
            "",
            List.of(),
            false,
            "",
            List.of(),
            List.of(),
            Session.Display.DEFAULTS);
    assertFalse(store.save(new Session(List.of(window), List.of(), List.of())));
    assertEquals(session(), store.load());
  }

  @Test
  void aReadOnlyDirectoryDisablesSavingAndTheFileStays() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
    assertTrue(store().save(session()));
    Set<PosixFilePermission> before = Files.getPosixFilePermissions(temp);
    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeFalse(Files.isWritable(temp), "the user ignores permissions");
      SessionStore store = store();
      assertEquals(session(), store.load());
      assertFalse(store.save(Session.EMPTY));
      assertEquals(session(), store.load());
    } finally {
      Files.setPosixFilePermissions(temp, before);
    }
  }

  @Test
  void selectionsKeepThePrimaryFirstAndClampToTheText() {
    SelectionSet set = SelectionSet.of(List.of(Selection.of(1, 2), Selection.of(8, 5)), 1);
    String text = SessionText.selections(set);
    assertEquals("8,5;1,2", text);
    assertEquals(set, SessionText.selections(text, 100));
    assertEquals(Selection.of(6, 5), SessionText.selections(text, 6).primary());
  }

  @Test
  void anUnknownCharsetNameReadsAsNone() {
    assertNull(SessionText.charset("x-mock-unknown"));
    assertNull(SessionText.charset("bad name"));
    assertEquals(StandardCharsets.UTF_16BE, SessionText.charset("UTF-16BE"));
  }
}
