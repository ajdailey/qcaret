package io.github.ajdailey.qcaret.app.editor;

/**
 * Makes every {@code <meta>} tag in an HTML fragment inert by renaming it to {@code <wbr>}, a void
 * element that renders nothing. The attributes stay, so the tag ends where the browser would end
 * it, and an {@code http-equiv="refresh"} on a {@code <wbr>} does nothing. The content security
 * policy does not stop a meta refresh.
 *
 * <p>The match is textual: {@code <meta} in any letter case, followed by whitespace, {@code /},
 * {@code >} or the end. Text inside an attribute value, a {@code <textarea>} or a {@code <script>}
 * changes too.
 */
final class InertMeta {
  private static final String META = "<meta";
  private static final String WBR = "<wbr";

  private InertMeta() {}

  /**
   * Returns the fragment with each {@code <meta} tag opener replaced by {@code <wbr}.
   *
   * @param html an HTML fragment
   * @return the fragment, the same instance when it holds no meta tag
   */
  static String apply(String html) {
    StringBuilder out = null;
    int copied = 0;
    int at = 0;
    while (true) {
      int open = html.indexOf('<', at);
      if (open < 0) {
        break;
      }
      at = open + 1;
      if (opensMeta(html, open)) {
        if (out == null) {
          out = new StringBuilder(html.length());
        }
        out.append(html, copied, open).append(WBR);
        copied = open + META.length();
        at = copied;
      }
    }
    if (out == null) {
      return html;
    }
    return out.append(html, copied, html.length()).toString();
  }

  private static boolean opensMeta(String html, int open) {
    int end = open + META.length();
    if (end > html.length() || !html.regionMatches(true, open, META, 0, META.length())) {
      return false;
    }
    if (end == html.length()) {
      return true;
    }
    char next = html.charAt(end);
    return next == ' '
        || next == '\t'
        || next == '\n'
        || next == '\f'
        || next == '\r'
        || next == '/'
        || next == '>';
  }
}
