package io.github.ajdailey.qcaret.app.i18n;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The test-only pseudo locale {@code en-XA}: each English value accented, about 40% longer and
 * bracketed, so a GUI test can tell bundle text from a literal, a clipped control and text joined
 * from fragments. {@link java.util.Formatter} specifiers and {@code &} mnemonics are copied
 * unchanged, and a trailing {@code ...} stays after the closing bracket.
 */
public final class PseudoLocale {
  /** The pseudo locale; {@code messages_en_XA} answers it. */
  public static final Locale LOCALE = Locale.forLanguageTag("en-XA");

  /** First character of every pseudo value. */
  public static final char OPEN = '[';

  /** Closing character of every pseudo value, before a trailing ellipsis. */
  public static final char CLOSE = ']';

  private static final String ELLIPSIS = "...";

  /** A {@link java.util.Formatter} specifier. */
  private static final Pattern SPECIFIER =
      Pattern.compile("%(\\d+\\$)?[-#+ 0,(<]*\\d*(\\.\\d+)?([tT][a-zA-Z]|[a-zA-Z%])");

  private static final String PLAIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final String ACCENTED =
      "ÅƁÇÐÉƑĜĤÎĴĶĻḾÑÖ" + "ÞǪŘŠŢÛṼŴẊÝŽ" + "åƀçðéƒĝĥîĵķļɱñö" + "þǫŕšţûṽŵẋýž";

  /** Padding words, cycled to the length a value grows by. */
  private static final String PADDING = " ļöŕéɱ îƀšûɱ";

  private PseudoLocale() {}

  /**
   * Translates an English bundle value.
   *
   * @param english the value
   * @return the bracketed, accented and padded value
   */
  public static String translate(String english) {
    String body = english;
    String tail = "";
    if (body.endsWith(ELLIPSIS)) {
      body = body.substring(0, body.length() - ELLIPSIS.length());
      tail = ELLIPSIS;
    }
    StringBuilder out = new StringBuilder().append(OPEN);
    int letters = 0;
    Matcher specifier = SPECIFIER.matcher(body);
    int i = 0;
    while (i < body.length()) {
      char c = body.charAt(i);
      if (c == '%' && specifier.find(i) && specifier.start() == i) {
        out.append(specifier.group());
        i = specifier.end();
      } else if (c == '&' && i + 1 < body.length() && body.charAt(i + 1) != '%') {
        out.append(c).append(body.charAt(i + 1));
        letters++;
        i += 2;
      } else {
        int plain = PLAIN.indexOf(c);
        out.append(plain < 0 ? c : ACCENTED.charAt(plain));
        letters++;
        i++;
      }
    }
    int pad = Math.max(1, Math.round(letters * 0.4f));
    for (int p = 0; p < pad; p++) {
      out.append(PADDING.charAt(p % PADDING.length()));
    }
    return out.append(CLOSE).append(tail).toString();
  }

  /**
   * Returns the {@link java.util.Formatter} specifiers of a value in order, joined by spaces.
   *
   * @param value a bundle value
   * @return the specifiers
   */
  public static String specifiers(String value) {
    StringBuilder found = new StringBuilder();
    Matcher specifier = SPECIFIER.matcher(value);
    while (specifier.find()) {
      found.append(specifier.group()).append(' ');
    }
    return found.toString();
  }

  /**
   * Returns the character after the mnemonic marker, or 0 without one; {@code &&} is a literal
   * ampersand.
   *
   * @param value a bundle value
   * @return the mnemonic character
   */
  public static char mnemonic(String value) {
    for (int i = 0; i + 1 < value.length(); i++) {
      if (value.charAt(i) == '&') {
        if (value.charAt(i + 1) != '&') {
          return value.charAt(i + 1);
        }
        i++;
      }
    }
    return 0;
  }
}
