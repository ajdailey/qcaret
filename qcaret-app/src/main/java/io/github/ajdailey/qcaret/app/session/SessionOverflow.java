package io.github.ajdailey.qcaret.app.session;

/**
 * What {@link SessionStore#save} leaves out of a session past its limits. A window past {@link
 * SessionStore#MAX_WINDOWS} counts as one window; its tabs and lists are not counted. Tabs count
 * over the windows kept, searches, expanded directories and closed files per window.
 *
 * @param windows windows left out
 * @param tabs tabs left out
 * @param searches retained searches left out
 * @param expanded expanded directories left out
 * @param recent Recent Files entries left out
 * @param closed closed files left out
 */
public record SessionOverflow(
    int windows, int tabs, int searches, int expanded, int recent, int closed) {
  /** Nothing left out. */
  public static final SessionOverflow NONE = new SessionOverflow(0, 0, 0, 0, 0, 0);

  /**
   * Counts what a save of {@code session} leaves out.
   *
   * @param session the session
   * @return the counts
   */
  public static SessionOverflow of(Session session) {
    int kept = Math.min(SessionStore.MAX_WINDOWS, session.windows().size());
    int tabs = 0;
    int searches = 0;
    int expanded = 0;
    int closed = 0;
    for (int w = 0; w < kept; w++) {
      Session.Window window = session.windows().get(w);
      tabs += window.tabs().size();
      searches += past(window.searches().size(), SessionStore.MAX_SEARCHES);
      expanded += past(window.expanded().size(), SessionStore.MAX_EXPANDED);
      closed += past(window.closed().size(), SessionStore.MAX_CLOSED);
    }
    return new SessionOverflow(
        session.windows().size() - kept,
        past(tabs, SessionStore.MAX_TABS),
        searches,
        expanded,
        past(session.recent().size(), SessionStore.MAX_RECENT),
        closed);
  }

  private static int past(int count, int max) {
    return Math.max(0, count - max);
  }

  /**
   * Reports whether nothing is left out.
   *
   * @return true when every count is 0
   */
  public boolean isEmpty() {
    return equals(NONE);
  }
}
