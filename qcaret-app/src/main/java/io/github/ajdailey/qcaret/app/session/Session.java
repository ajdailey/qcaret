package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@code qcaret_session.conf} holds. Immutable.
 *
 * @param windows the main windows, in order
 * @param recent File > Recent Files, newest first
 * @param files per-file state, most recently used first
 * @param droppedComments the comments the save of this file left out, not yet reported; {@link
 *     SessionStore#save} writes this count plus those it leaves out itself
 */
public record Session(
    List<Window> windows, List<String> recent, List<FileState> files, int droppedComments) {
  /** Nothing remembered. */
  public static final Session EMPTY = new Session(List.of(), List.of(), List.of());

  /** Copies the lists. */
  public Session {
    windows = List.copyOf(windows);
    recent = List.copyOf(recent);
    files = List.copyOf(files);
  }

  /**
   * Creates a session with no comments to report.
   *
   * @param windows the main windows, in order
   * @param recent File > Recent Files, newest first
   * @param files per-file state, most recently used first
   */
  public Session(List<Window> windows, List<String> recent, List<FileState> files) {
    this(windows, recent, files, 0);
  }

  /**
   * Returns a copy whose windows hold no retained searches.
   *
   * @return the copy
   */
  public Session withoutSearches() {
    List<Window> list = new ArrayList<Window>();
    for (Window window : windows) {
      list.add(window.withoutSearches());
    }
    return new Session(list, recent, files, droppedComments);
  }

  /**
   * Returns a copy holding only Recent Files.
   *
   * @return the copy
   */
  public Session recentOnly() {
    return new Session(List.of(), recent, List.of());
  }

  /**
   * One main window.
   *
   * @param bounds its normal bounds and maximized state
   * @param tabs its tabs, in order
   * @param active the selected tab, -1 when none is known
   * @param workspace the workspace folder, empty for none
   * @param browser true when the workspace browser shows
   * @param sideWeights the browser and the editors, {@code a,b}; empty when unknown
   * @param expanded the browser's expanded directories, parents first
   * @param results true when the results dock shows
   * @param dockWeights the editors and the docks, {@code a,b}; empty when unknown
   * @param searches the retained HyperSearch requests, newest first
   * @param closed Reopen Closed Tab's files, newest first
   * @param display what the window's editors show
   * @param resultsWeights the results and Bookmarks docks, {@code a,b}; empty when unknown
   */
  public record Window(
      WindowState bounds,
      List<Tab> tabs,
      int active,
      String workspace,
      boolean browser,
      String sideWeights,
      List<String> expanded,
      boolean results,
      String dockWeights,
      List<Search> searches,
      List<String> closed,
      Display display,
      String resultsWeights) {
    /** Copies the lists. */
    public Window {
      tabs = List.copyOf(tabs);
      expanded = List.copyOf(expanded);
      searches = List.copyOf(searches);
      closed = List.copyOf(closed);
    }

    /**
     * Returns a copy without its retained searches.
     *
     * @return the copy
     */
    public Window withoutSearches() {
      return new Window(
          bounds,
          tabs,
          active,
          workspace,
          browser,
          sideWeights,
          expanded,
          results,
          dockWeights,
          List.of(),
          closed,
          display,
          resultsWeights);
    }
  }

  /**
   * What a window's editors show.
   *
   * @param wrap word wrap
   * @param whitespace the whitespace marks
   * @param lineEndings the line-ending marks
   * @param overview the overview strip
   * @param bookmarks the Bookmarks list
   * @param lineNumbers the line numbers
   */
  public record Display(
      boolean wrap,
      boolean whitespace,
      boolean lineEndings,
      boolean overview,
      boolean bookmarks,
      boolean lineNumbers) {
    /** A window that saved nothing. */
    public static final Display DEFAULTS = new Display(false, false, false, true, false, true);
  }

  /**
   * One tab.
   *
   * @param path the file as the user opened it; empty for an untitled document
   * @param title the title of an untitled document
   * @param buffer the id of the buffer's snapshot file, empty when the tab reads its file
   * @param encoding the charset the user forced, empty for detection
   * @param language the grammar the user chose, empty for detection
   * @param binary true for a binary file's read-only view
   * @param tabWidth columns per tab stop
   * @param insertSpaces true to indent with spaces
   * @param views one view or two
   * @param focused the index of the focused view
   * @param sideBySide true when two views stand side by side
   * @param weights the two views' sash weights, {@code a,b}; empty for one view
   * @param bookmarks the bookmarked lines, zero-based, comma-separated
   * @param comments the bookmark comments
   * @param readOnly true when the document refuses edits
   */
  public record Tab(
      String path,
      String title,
      String buffer,
      String encoding,
      String language,
      boolean binary,
      int tabWidth,
      boolean insertSpaces,
      List<View> views,
      int focused,
      boolean sideBySide,
      String weights,
      String bookmarks,
      List<Comment> comments,
      boolean readOnly) {
    /** Copies the lists. */
    public Tab {
      views = List.copyOf(views);
      comments = List.copyOf(comments);
    }

    /**
     * Returns a copy that reads a snapshot file.
     *
     * @param id the snapshot's buffer id
     * @return the copy
     */
    public Tab withBuffer(String id) {
      return new Tab(
          path,
          title,
          id,
          encoding,
          language,
          binary,
          tabWidth,
          insertSpaces,
          views,
          focused,
          sideBySide,
          weights,
          bookmarks,
          comments,
          readOnly);
    }
  }

  /**
   * One editor view.
   *
   * @param selections in the form of {@link SessionText#selections}
   * @param top the first visible line, zero-based
   * @param folds the first lines of the collapsed regions, comma-separated
   */
  public record View(String selections, int top, String folds) {}

  /**
   * A bookmark's comment.
   *
   * @param line the zero-based line
   * @param text the comment
   */
  public record Comment(int line, String text) {}

  /**
   * A retained HyperSearch.
   *
   * @param request the search
   * @param pinned true when the set is pinned
   */
  public record Search(SearchRequest request, boolean pinned) {}

  /**
   * One file's state in the LRU.
   *
   * @param path the opened document's real path
   * @param encoding the charset the user forced, empty for detection
   * @param language the grammar the user chose, empty for detection
   * @param tabWidth columns per tab stop
   * @param insertSpaces true to indent with spaces
   * @param selections in the form of {@link SessionText#selections}
   * @param top the first visible line, zero-based
   */
  public record FileState(
      String path,
      String encoding,
      String language,
      int tabWidth,
      boolean insertSpaces,
      String selections,
      int top) {
    /**
     * Returns this state for another path, as a rename moves it.
     *
     * @param moved the file's new real path
     * @return the state with the new path
     */
    public FileState withPath(String moved) {
      return new FileState(moved, encoding, language, tabWidth, insertSpaces, selections, top);
    }
  }
}
