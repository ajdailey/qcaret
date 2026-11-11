package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes the retained searches from {@value SessionStore#FILE} for Clear History. The owner of the
 * session rewrites the file directly; any other instance rewrites it only while it holds {@link
 * SessionLock}. A lock file that cannot be created or locked leaves the rewrite unguarded, with one
 * WARN.
 */
public final class SessionRewrite {
  /** How a removal ended. */
  public enum Result {
    /** The file holds no retained search. */
    REMOVED,
    /** The write failed; the file keeps its searches. */
    FAILED,
    /** Another instance holds {@link SessionLock}; nothing was written. */
    OWNED_ELSEWHERE
  }

  private static final Logger LOG = LoggerFactory.getLogger(SessionRewrite.class);

  private SessionRewrite() {}

  /**
   * Rewrites the file without its retained searches; a file without any is left as it is. Blocking.
   *
   * @param store the session file
   * @param owner true when this instance owns the session and holds the lock already
   * @return the result
   */
  public static Result withoutSearches(SessionStore store, boolean owner) {
    if (owner) {
      return strip(store);
    }
    SessionLock lock;
    try {
      lock = SessionLock.acquire(store.location());
    } catch (IOException e) {
      LOG.warn(
          "Session lock unavailable; rewriting without it: {}", LogText.oneLine(e.getMessage()));
      return strip(store);
    }
    if (lock == null) {
      LOG.info("Another instance owns the session; its retained searches stay in the file");
      return Result.OWNED_ELSEWHERE;
    }
    try {
      return strip(store);
    } finally {
      release(lock);
    }
  }

  private static Result strip(SessionStore store) {
    Session file = store.load();
    Session cleared = file.withoutSearches();
    return cleared.equals(file) || store.save(cleared) ? Result.REMOVED : Result.FAILED;
  }

  private static void release(SessionLock lock) {
    try {
      lock.close();
    } catch (IOException e) {
      LOG.warn("Cannot release the session lock: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
