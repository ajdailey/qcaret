package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.InstallPaths;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a {@link Session} into the keys of {@code qcaret_session.conf}, keeping the {@link
 * SessionStore} limits and counting the comments left out as {@code droppedComments}. Each file and
 * folder path is written through {@link InstallPaths#write}.
 */
final class SessionWriter {
  private SessionWriter() {}

  /** Returns the keys of a session, {@code version} included. */
  static Map<String, String> keys(Session session, InstallPaths paths) {
    Map<String, String> keys = new TreeMap<String, String>();
    keys.put(SessionStore.VERSION_KEY, SessionStore.VERSION);
    list(keys, SessionStore.RECENT, session.recent(), SessionStore.MAX_RECENT, paths);
    int files = Math.min(SessionStore.MAX_FILES, session.files().size());
    for (int i = 0; i < files; i++) {
      Session.FileState f = session.files().get(i);
      String key = SessionStore.FILE_KEY + i + ".";
      keys.put(key + "path", paths.write(f.path()));
      put(keys, key + "encoding", f.encoding());
      put(keys, key + "language", f.language());
      keys.put(key + "tabWidth", Integer.toString(f.tabWidth()));
      keys.put(key + "spaces", Boolean.toString(f.insertSpaces()));
      put(keys, key + "selection", f.selections());
      keys.put(key + "top", Integer.toString(f.top()));
    }
    int tabs = 0;
    int comments = 0;
    int windows = Math.min(SessionStore.MAX_WINDOWS, session.windows().size());
    for (int w = 0; w < windows; w++) {
      Session.Window window = session.windows().get(w);
      String key = SessionStore.WINDOW + w + ".";
      WindowState bounds = window.bounds();
      optional(keys, key + "x", bounds.x());
      optional(keys, key + "y", bounds.y());
      optional(keys, key + "width", bounds.width());
      optional(keys, key + "height", bounds.height());
      keys.put(key + "maximized", Boolean.toString(bounds.maximized()));
      keys.put(key + "active", Integer.toString(window.active()));
      put(keys, key + "workspace", paths.write(window.workspace()));
      keys.put(key + "browser", Boolean.toString(window.browser()));
      put(keys, key + "sideWeights", window.sideWeights());
      list(keys, key + "expanded.", window.expanded(), SessionStore.MAX_EXPANDED, paths);
      keys.put(key + "results", Boolean.toString(window.results()));
      put(keys, key + "dockWeights", window.dockWeights());
      put(keys, key + "resultsWeights", window.resultsWeights());
      put(keys, key + "browseFolder", paths.write(window.browse().folder()));
      put(keys, key + "browseFilter", window.browse().filter());
      keys.put(key + "browseHidden", Boolean.toString(window.browse().hidden()));
      list(keys, key + "closed.", window.closed(), SessionStore.MAX_CLOSED, paths);
      Session.Display display = window.display();
      keys.put(key + "wrap", Boolean.toString(display.wrap()));
      keys.put(key + "whitespace", Boolean.toString(display.whitespace()));
      keys.put(key + "lineEndings", Boolean.toString(display.lineEndings()));
      keys.put(key + "overview", Boolean.toString(display.overview()));
      keys.put(key + "bookmarks", Boolean.toString(display.bookmarks()));
      keys.put(key + "lineNumbers", Boolean.toString(display.lineNumbers()));
      keys.put(key + "onTop", Boolean.toString(window.onTop()));
      for (int i = 0; i < Math.min(SessionStore.MAX_SEARCHES, window.searches().size()); i++) {
        search(keys, key + "search." + i + ".", window.searches().get(i), paths);
      }
      int count = Math.min(SessionStore.MAX_TABS - tabs, window.tabs().size());
      for (int i = 0; i < count; i++) {
        comments += tab(keys, key + "tab." + i + ".", window.tabs().get(i), comments, paths);
      }
      tabs += count;
    }
    int dropped = session.droppedComments() + commentCount(session) - comments;
    if (dropped > 0) {
      keys.put(SessionStore.DROPPED_COMMENTS, Integer.toString(dropped));
    }
    return keys;
  }

  private static int commentCount(Session session) {
    int count = 0;
    for (Session.Window window : session.windows()) {
      for (Session.Tab tab : window.tabs()) {
        count += tab.comments().size();
      }
    }
    return count;
  }

  /** Writes a tab and returns the comments written. */
  private static int tab(
      Map<String, String> keys, String key, Session.Tab tab, int written, InstallPaths paths) {
    put(keys, key + "path", paths.write(tab.path()));
    put(keys, key + "title", tab.title());
    put(keys, key + "buffer", tab.buffer());
    put(keys, key + "encoding", tab.encoding());
    put(keys, key + "language", tab.language());
    keys.put(key + "binary", Boolean.toString(tab.binary()));
    keys.put(key + "readOnly", Boolean.toString(tab.readOnly()));
    keys.put(key + "tabWidth", Integer.toString(tab.tabWidth()));
    keys.put(key + "spaces", Boolean.toString(tab.insertSpaces()));
    keys.put(key + "focused", Integer.toString(tab.focused()));
    keys.put(key + "sideBySide", Boolean.toString(tab.sideBySide()));
    put(keys, key + "weights", tab.weights());
    put(keys, key + "bookmarks", tab.bookmarks());
    for (int v = 0; v < Math.min(2, tab.views().size()); v++) {
      Session.View view = tab.views().get(v);
      String viewKey = key + "view." + v + ".";
      keys.put(viewKey + "selection", view.selections());
      keys.put(viewKey + "top", Integer.toString(view.top()));
      put(keys, viewKey + "folds", view.folds());
    }
    int count = Math.min(SessionStore.MAX_COMMENTS - written, tab.comments().size());
    for (int k = 0; k < count; k++) {
      Session.Comment comment = tab.comments().get(k);
      keys.put(key + "comment." + k, comment.line() + " " + comment.text());
    }
    return count;
  }

  private static void search(
      Map<String, String> keys, String key, Session.Search search, InstallPaths paths) {
    SearchRequest r = search.request();
    keys.put(key + "text", r.text());
    keys.put(key + "regex", Boolean.toString(r.regex()));
    keys.put(key + "case", Boolean.toString(r.caseSensitive()));
    keys.put(key + "word", Boolean.toString(r.wholeWord()));
    keys.put(key + "scope", r.scope().name());
    put(keys, key + "folder", r.folder() == null ? "" : paths.write(r.folder().toString()));
    put(keys, key + "include", r.include());
    put(keys, key + "exclude", r.exclude());
    put(keys, key + "excluded", r.excludedDirectories());
    keys.put(key + "hidden", Boolean.toString(r.hidden()));
    keys.put(key + "pinned", Boolean.toString(search.pinned()));
  }

  private static void put(Map<String, String> keys, String key, String value) {
    if (!value.isEmpty()) {
      keys.put(key, value);
    }
  }

  private static void optional(Map<String, String> keys, String key, Integer value) {
    if (value != null) {
      keys.put(key, value.toString());
    }
  }

  private static void list(
      Map<String, String> keys, String prefix, List<String> list, int max, InstallPaths paths) {
    for (int i = 0; i < Math.min(max, list.size()); i++) {
      keys.put(prefix + i, paths.write(list.get(i)));
    }
  }
}
