package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.edit.Brackets;

/**
 * Leaves out of bracket matching the brackets whose token is a comment, string or escape. A bracket
 * on a line without tokens counts. Remembers the last line it looked up, so a scan reads each
 * line's tokens once. Confined to the thread that reads {@code tokens}.
 */
public final class TokenBracketFilter implements Brackets.Filter {
  private static final int COMMENT = TokenRole.COMMENT.ordinal();
  private static final int STRING = TokenRole.STRING.ordinal();
  private static final int ESCAPE = TokenRole.ESCAPE.ordinal();

  private final TextContent text;
  private final TokenSource tokens;
  private final LineView view = new LineView();
  private int lineStart = 0;
  private int lineEnd = -1;
  private int[] lineTokens;

  /**
   * Creates the filter.
   *
   * @param text the text matched, at the revision of the tokens
   * @param tokens the tokens by line
   */
  public TokenBracketFilter(TextContent text, TokenSource tokens) {
    this.text = text;
    this.tokens = tokens;
  }

  @Override
  public boolean counts(int offset) {
    if (offset < lineStart || offset > lineEnd) {
      int line = text.lineAt(offset);
      text.lineView(line, view);
      lineStart = view.start();
      lineEnd = view.start() + view.length();
      lineTokens = tokens.tokens(line);
    }
    int[] t = lineTokens;
    if (t == null) {
      return true;
    }
    int column = offset - lineStart;
    int lo = 0;
    int hi = t.length / 3;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (t[mid * 3 + 1] <= column) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo * 3 >= t.length || t[lo * 3] > column) {
      return true;
    }
    int role = t[lo * 3 + 2];
    return role != COMMENT && role != STRING && role != ESCAPE;
  }
}
