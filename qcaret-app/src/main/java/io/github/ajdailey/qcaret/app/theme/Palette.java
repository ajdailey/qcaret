package io.github.ajdailey.qcaret.app.theme;

/**
 * Colors of the text views. The views create their toolkit colors from it and recreate them when
 * the palette changes.
 *
 * @param dark true for a dark palette; selects the matching icon variant
 * @param text text
 * @param background view background
 * @param selection selected text background
 * @param selectionText selected text
 * @param currentLine background of the caret's line
 * @param lineNumbers line number text
 * @param gutter line number background
 */
public record Palette(
    boolean dark,
    Rgb text,
    Rgb background,
    Rgb selection,
    Rgb selectionText,
    Rgb currentLine,
    Rgb lineNumbers,
    Rgb gutter) {
  /** The default palette. */
  public static final Palette LIGHT =
      new Palette(
          false,
          Rgb.of(0x1e1e1e),
          Rgb.of(0xffffff),
          Rgb.of(0x3874d8),
          Rgb.of(0xffffff),
          Rgb.of(0xeef3fb),
          Rgb.of(0x8a8a8a),
          Rgb.of(0xf3f3f3));

  /** The dark palette. */
  public static final Palette DARK =
      new Palette(
          true,
          Rgb.of(0xd4d4d4),
          Rgb.of(0x1e1e1e),
          Rgb.of(0x264f78),
          Rgb.of(0xffffff),
          Rgb.of(0x2a2d2e),
          Rgb.of(0x858585),
          Rgb.of(0x252526));
}
