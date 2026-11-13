package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Builds a {@link Session} from the properties of {@code qcaret_session.conf}, within the {@link
 * SessionStore} limits. A value that does not parse falls back to its default; a tab without a path
 * or buffer and a search whose scope or folder does not parse are skipped.
 */
final class SessionReader {
  private SessionReader() {}

  /** Returns the session the properties hold. */
  static Session parse(Properties p) {
    List<Session.Window> windows = new ArrayList<Session.Window>();
    int tabs = 0;
    int comments = 0;
    for (int w = 0; w < SessionStore.MAX_WINDOWS; w++) {
      String key = SessionStore.WINDOW + w + ".";
      if (p.getProperty(key + "active") == null) {
        break;
      }
      List<Session.Tab> list = new ArrayList<Session.Tab>();
      for (int i = 0; tabs < SessionStore.MAX_TABS; i++) {
        String tabKey = key + "tab." + i + ".";
        if (p.getProperty(tabKey + "tabWidth") == null) {
          break;
        }
        Session.Tab tab = tab(p, tabKey, SessionStore.MAX_COMMENTS - comments);
        if (!tab.path().isEmpty() || !tab.buffer().isEmpty()) {
          list.add(tab);
          tabs++;
          comments += tab.comments().size();
        }
      }
      windows.add(window(p, key, list));
    }
    return new Session(
        windows,
        list(p, SessionStore.RECENT, SessionStore.MAX_RECENT),
        files(p),
        number(p, SessionStore.DROPPED_COMMENTS, 0, Integer.MAX_VALUE, 0));
  }

  private static Session.Window window(Properties p, String key, List<Session.Tab> tabs) {
    List<Session.Search> searches = new ArrayList<Session.Search>();
    for (int i = 0; i < SessionStore.MAX_SEARCHES; i++) {
      String searchKey = key + "search." + i + ".";
      if (p.getProperty(searchKey + "text") == null) {
        break;
      }
      Session.Search search = search(p, searchKey);
      if (search != null) {
        searches.add(search);
      }
    }
    return new Session.Window(
        new WindowState(
            pixels(p, key + "x", -PIXELS),
            pixels(p, key + "y", -PIXELS),
            pixels(p, key + "width", 1),
            pixels(p, key + "height", 1),
            flag(p, key + "maximized")),
        tabs,
        number(p, key + "active", -1, SessionStore.MAX_TABS, -1),
        p.getProperty(key + "workspace", ""),
        flag(p, key + "browser"),
        p.getProperty(key + "sideWeights", ""),
        list(p, key + "expanded.", SessionStore.MAX_EXPANDED),
        flag(p, key + "results"),
        p.getProperty(key + "dockWeights", ""),
        searches,
        list(p, key + "closed.", SessionStore.MAX_CLOSED),
        new Session.Display(
            flag(p, key + "wrap"),
            flag(p, key + "whitespace"),
            flag(p, key + "lineEndings"),
            !"false".equals(p.getProperty(key + "overview", "true").strip()),
            flag(p, key + "bookmarks"),
            !"false".equals(p.getProperty(key + "lineNumbers", "true").strip())),
        p.getProperty(key + "resultsWeights", ""),
        new Session.Browse(
            p.getProperty(key + "browseFolder", ""),
            p.getProperty(key + "browseFilter", ""),
            flag(p, key + "browseHidden")),
        flag(p, key + "onTop"));
  }

  private static Session.Tab tab(Properties p, String key, int maxComments) {
    List<Session.View> views = new ArrayList<Session.View>();
    for (int v = 0; v < 2; v++) {
      String viewKey = key + "view." + v + ".";
      if (p.getProperty(viewKey + "selection") == null) {
        break;
      }
      views.add(
          new Session.View(
              p.getProperty(viewKey + "selection"),
              number(p, viewKey + "top", 0, Integer.MAX_VALUE, 0),
              p.getProperty(viewKey + "folds", "")));
    }
    List<Session.Comment> comments = new ArrayList<Session.Comment>();
    for (int k = 0; comments.size() < maxComments; k++) {
      String value = p.getProperty(key + "comment." + k);
      if (value == null) {
        break;
      }
      int space = value.indexOf(' ');
      if (space > 0) {
        int[] line = SessionText.numbers(value.substring(0, space));
        if (line.length == 1) {
          comments.add(new Session.Comment(line[0], value.substring(space + 1)));
        }
      }
    }
    return new Session.Tab(
        p.getProperty(key + "path", ""),
        p.getProperty(key + "title", ""),
        p.getProperty(key + "buffer", ""),
        p.getProperty(key + "encoding", ""),
        p.getProperty(key + "language", ""),
        flag(p, key + "binary"),
        number(p, key + "tabWidth", 1, Document.MAX_TAB_WIDTH, Document.DEFAULT_TAB_WIDTH),
        flag(p, key + "spaces"),
        views,
        number(p, key + "focused", 0, 1, 0),
        flag(p, key + "sideBySide"),
        p.getProperty(key + "weights", ""),
        p.getProperty(key + "bookmarks", ""),
        comments,
        flag(p, key + "readOnly"));
  }

  /** Returns a search, or null when its scope or folder does not parse. */
  private static Session.Search search(Properties p, String key) {
    try {
      String folder = p.getProperty(key + "folder", "");
      return new Session.Search(
          new SearchRequest(
              p.getProperty(key + "text"),
              flag(p, key + "regex"),
              flag(p, key + "case"),
              flag(p, key + "word"),
              SearchScope.valueOf(p.getProperty(key + "scope", "")),
              folder.isEmpty() ? null : Path.of(folder),
              p.getProperty(key + "include", ""),
              p.getProperty(key + "exclude", ""),
              p.getProperty(key + "excluded", ""),
              flag(p, key + "hidden")),
          flag(p, key + "pinned"));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static List<Session.FileState> files(Properties p) {
    List<Session.FileState> files = new ArrayList<Session.FileState>();
    for (int i = 0; i < SessionStore.MAX_FILES; i++) {
      String key = SessionStore.FILE_KEY + i + ".";
      String path = p.getProperty(key + "path");
      if (path == null) {
        break;
      }
      files.add(
          new Session.FileState(
              path,
              p.getProperty(key + "encoding", ""),
              p.getProperty(key + "language", ""),
              number(p, key + "tabWidth", 1, Document.MAX_TAB_WIDTH, Document.DEFAULT_TAB_WIDTH),
              flag(p, key + "spaces"),
              p.getProperty(key + "selection", ""),
              number(p, key + "top", 0, Integer.MAX_VALUE, 0)));
    }
    return files;
  }

  private static List<String> list(Properties p, String prefix, int max) {
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < max; i++) {
      String value = p.getProperty(prefix + i);
      if (value == null) {
        break;
      }
      list.add(value);
    }
    return list;
  }

  private static boolean flag(Properties p, String key) {
    return "true".equals(p.getProperty(key, "false").strip());
  }

  private static final int PIXELS = 100_000;

  private static Integer pixels(Properties p, String key, int min) {
    int value = number(p, key, min, PIXELS, Integer.MIN_VALUE);
    return value == Integer.MIN_VALUE ? null : Integer.valueOf(value);
  }

  private static int number(Properties p, String key, int min, int max, int fallback) {
    String value = p.getProperty(key);
    if (value == null) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(value.strip());
      return parsed >= min && parsed <= max ? (int) parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
