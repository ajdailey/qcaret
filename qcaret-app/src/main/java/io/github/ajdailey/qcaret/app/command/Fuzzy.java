package io.github.ajdailey.qcaret.app.command;

/**
 * Fuzzy matching for the command palette: a query matches a text when its non-blank chars appear in
 * the text in order, ignoring case.
 */
public final class Fuzzy {
  private Fuzzy() {}

  /**
   * Scores a match, taking each query char at its first possible place. A char scores 1, 3 more
   * right after the previous match and 5 more at a word start.
   *
   * @param query the query; blanks are skipped
   * @param text the text
   * @return the score, 0 for an empty query, or -1 when the text does not match
   */
  public static int score(String query, String text) {
    int score = 0;
    int at = 0;
    int previous = -2;
    for (int i = 0; i < query.length(); i++) {
      char q = Character.toLowerCase(query.charAt(i));
      if (Character.isWhitespace(q)) {
        continue;
      }
      while (at < text.length() && Character.toLowerCase(text.charAt(at)) != q) {
        at++;
      }
      if (at == text.length()) {
        return -1;
      }
      score++;
      if (at == previous + 1) {
        score += 3;
      }
      if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))) {
        score += 5;
      }
      previous = at;
      at++;
    }
    return score;
  }
}
