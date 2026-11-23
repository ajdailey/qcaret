package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.format.Formats;
import io.github.ajdailey.qcaret.core.format.KeptContent;
import java.util.Locale;

/** The notice of a Pretty Print or Minify run that changed nothing and failed nothing. */
final class FormatUnchanged {
  private FormatUnchanged() {}

  /**
   * Returns the notice: the first content kept as written, or that the text is already formatted or
   * minified.
   *
   * @param mode {@link Formats.Mode#PRETTY_PRINT} or {@link Formats.Mode#MINIFY}
   * @param kept the run's {@link io.github.ajdailey.qcaret.core.format.FormatResult#kept}, or null
   * @return the notice
   */
  static String notice(Formats.Mode mode, KeptContent kept) {
    if (kept == null) {
      return mode == Formats.Mode.MINIFY
          ? UiText.FORMAT_ALREADY_MINIFIED.text()
          : UiText.FORMAT_ALREADY_FORMATTED.text();
    }
    String subject =
        kept.element() == null
            ? UiText.FORMAT_KEPT_SELECTION.text()
            : UiText.FORMAT_KEPT_ELEMENT.format(kept.element());
    return switch (kept.kind()) {
      case PRESERVE -> UiText.FORMAT_KEPT_PRESERVE.format(subject, kept.line());
      case CDATA -> UiText.FORMAT_KEPT_CDATA.format(subject, kept.line());
      case TEXT ->
          kept.character() < 0
              ? UiText.FORMAT_KEPT_TEXT.format(subject, kept.line())
              : UiText.FORMAT_KEPT_CHARACTER.format(
                  subject, kept.line(), character(kept.character()));
    };
  }

  /** Returns a char as {@code U+00A0 NO-BREAK SPACE}, or the code alone when it has no name. */
  static String character(int c) {
    String name = Character.getName(c);
    String code = String.format(Locale.ROOT, "U+%04X", c);
    return name == null ? code : code + " " + name;
  }
}
