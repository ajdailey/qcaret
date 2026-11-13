package io.github.ajdailey.qcaret.app.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each {@link SessionStore} limit reached by one entry past it: {@link SessionOverflow} counts what
 * the save leaves out, and the file read back holds exactly the limit.
 */
class SessionOverflowTest {
  @TempDir Path temp;

  private Session saveAndLoad(Session session) {
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
    assertTrue(store.save(session));
    return store.load();
  }

  private static List<String> names(String prefix, int count) {
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      names.add("/mock/" + prefix + i);
    }
    return names;
  }

  private static List<Session.Tab> tabs(int count) {
    List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    for (String name : names("tab", count)) {
      tabs.add(
          new Session.Tab(
              name, "", "", "", "", false, 4, false, List.of(), 0, false, "", "", List.of(),
              false));
    }
    return tabs;
  }

  private static List<Session.Search> searches(int count) {
    List<Session.Search> searches = new ArrayList<Session.Search>();
    for (int i = 0; i < count; i++) {
      searches.add(
          new Session.Search(
              new SearchRequest(
                  "needle" + i,
                  false,
                  false,
                  false,
                  SearchScope.OPEN_DOCUMENTS,
                  null,
                  "",
                  "",
                  "",
                  false),
              true));
    }
    return searches;
  }

  private static Session.Window window(
      List<Session.Tab> tabs,
      List<String> expanded,
      List<Session.Search> searches,
      List<String> closed) {
    return new Session.Window(
        WindowState.UNKNOWN,
        tabs,
        0,
        "",
        false,
        "",
        expanded,
        false,
        "",
        searches,
        closed,
        Session.Display.DEFAULTS,
        "");
  }

  private static Session.Window plain() {
    return window(tabs(1), List.of(), List.of(), List.of());
  }

  private static Session session(List<Session.Window> windows) {
    return new Session(windows, List.of(), List.of());
  }

  @Test
  void aSessionInsideTheLimitsLeavesNothingOut() {
    Session session =
        new Session(
            List.of(
                window(
                    tabs(SessionStore.MAX_TABS),
                    names("x", SessionStore.MAX_EXPANDED),
                    searches(SessionStore.MAX_SEARCHES),
                    names("c", SessionStore.MAX_CLOSED))),
            names("r", SessionStore.MAX_RECENT),
            List.of());
    assertTrue(SessionOverflow.of(session).isEmpty());
  }

  @Test
  void windowsPastTheLimit() {
    List<Session.Window> windows = new ArrayList<Session.Window>();
    for (int i = 0; i <= SessionStore.MAX_WINDOWS; i++) {
      windows.add(plain());
    }
    Session session = session(windows);
    assertEquals(new SessionOverflow(1, 0, 0, 0, 0, 0), SessionOverflow.of(session));
    assertEquals(SessionStore.MAX_WINDOWS, saveAndLoad(session).windows().size());
  }

  @Test
  void tabsPastTheLimitCountOverAllWindows() {
    Session.Window full = window(tabs(SessionStore.MAX_TABS - 1), List.of(), List.of(), List.of());
    Session session = session(List.of(full, window(tabs(3), List.of(), List.of(), List.of())));
    assertEquals(new SessionOverflow(0, 2, 0, 0, 0, 0), SessionOverflow.of(session));
    Session read = saveAndLoad(session);
    assertEquals(
        SessionStore.MAX_TABS,
        read.windows().get(0).tabs().size() + read.windows().get(1).tabs().size());
  }

  @Test
  void searchesPastTheLimitCountPerWindow() {
    Session.Window over =
        window(tabs(1), List.of(), searches(SessionStore.MAX_SEARCHES + 1), List.of());
    Session session = session(List.of(over, over));
    assertEquals(new SessionOverflow(0, 0, 2, 0, 0, 0), SessionOverflow.of(session));
    assertEquals(
        SessionStore.MAX_SEARCHES, saveAndLoad(session).windows().get(1).searches().size());
  }

  @Test
  void expandedDirectoriesPastTheLimit() {
    Session.Window over =
        window(tabs(1), names("x", SessionStore.MAX_EXPANDED + 1), List.of(), List.of());
    Session session = session(List.of(over));
    assertEquals(new SessionOverflow(0, 0, 0, 1, 0, 0), SessionOverflow.of(session));
    assertEquals(
        SessionStore.MAX_EXPANDED, saveAndLoad(session).windows().get(0).expanded().size());
  }

  @Test
  void recentFilesPastTheLimit() {
    Session session =
        new Session(List.of(plain()), names("r", SessionStore.MAX_RECENT + 1), List.of());
    assertEquals(new SessionOverflow(0, 0, 0, 0, 1, 0), SessionOverflow.of(session));
    assertEquals(SessionStore.MAX_RECENT, saveAndLoad(session).recent().size());
  }

  @Test
  void closedFilesPastTheLimit() {
    Session.Window over =
        window(tabs(1), List.of(), List.of(), names("c", SessionStore.MAX_CLOSED + 1));
    Session session = session(List.of(over));
    assertEquals(new SessionOverflow(0, 0, 0, 0, 0, 1), SessionOverflow.of(session));
    assertEquals(SessionStore.MAX_CLOSED, saveAndLoad(session).windows().get(0).closed().size());
  }
}
