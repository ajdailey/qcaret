package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.GapBufferStorage;
import io.github.ajdailey.qcaret.core.buffer.PieceTreeStorage;
import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextStorage;

/** The storage candidates under test. */
public enum Candidate {
  PIECE_TREE,
  ROPE,
  GAP_BUFFER;

  /**
   * Builds this candidate from decoded text.
   *
   * @param text the text
   * @return the storage
   */
  public TextStorage create(CharSequence text) {
    switch (this) {
      case PIECE_TREE:
        return PieceTreeStorage.of(text);
      case ROPE:
        return RopeStorage.of(text);
      default:
        return GapBufferStorage.of(text);
    }
  }
}
