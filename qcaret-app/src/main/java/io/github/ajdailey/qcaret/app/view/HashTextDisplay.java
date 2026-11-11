package io.github.ajdailey.qcaret.app.view;

/** An open Tools > Generate dialog, as its controller updates it. Call on the UI thread. */
public interface HashTextDisplay {
  /**
   * Shows the hash of the field's current text.
   *
   * @param hex the digest in lower-case hex
   */
  void showHash(String hex);
}
