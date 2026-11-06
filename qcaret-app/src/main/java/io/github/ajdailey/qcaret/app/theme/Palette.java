package io.github.ajdailey.qcaret.app.theme;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The editor color scheme: the colors of the text views and a {@link TokenStyle} for every {@link
 * TokenRole}. The views create their toolkit colors from it and recreate them when the palette
 * changes. The defaults derive from the brand colors navy {@code #1B2E44}, cyan {@code #56C1F0} and
 * amber {@code #F3B65B}; every token color has a contrast ratio of at least 4.5 against the
 * background, the current line, a find match, an occurrence and a bracket highlight.
 *
 * @param dark true for a dark palette; selects the matching icon variant
 * @param text text that is no token
 * @param background view background
 * @param selection selected text background
 * @param selectionText selected text
 * @param currentLine background of the caret's line
 * @param lineNumbers line number text
 * @param gutter line number background
 * @param caret the primary and secondary carets
 * @param whitespace whitespace marks
 * @param findMatch background of a find match
 * @param occurrence background of the occurrences of the word at the caret
 * @param bracket background of the bracket at the caret and its partner
 * @param tokens the style of each role; every role has one
 * @param accents the colors of the tabs, the overview strip, bookmarks and the comparison window
 */
public record Palette(
    boolean dark,
    Rgb text,
    Rgb background,
    Rgb selection,
    Rgb selectionText,
    Rgb currentLine,
    Rgb lineNumbers,
    Rgb gutter,
    Rgb caret,
    Rgb whitespace,
    Rgb findMatch,
    Rgb occurrence,
    Rgb bracket,
    Map<TokenRole, TokenStyle> tokens,
    Palette.Accents accents) {
  /**
   * The colors outside the text views' own.
   *
   * @param tabText the label of an unselected tab
   * @param overviewFind the overview strip's mark of a find-bar match
   * @param overviewSearch the overview strip's mark of a HyperSearch match
   * @param bookmarkComment the gutter mark of a bookmark with a comment
   * @param diffRemoved the comparison window's background of a line on the left only
   * @param diffAdded the comparison window's background of a line on the right only
   * @param diffChanged the comparison window's background of a line that differs
   */
  public record Accents(
      Rgb tabText,
      Rgb overviewFind,
      Rgb overviewSearch,
      Rgb bookmarkComment,
      Rgb diffRemoved,
      Rgb diffAdded,
      Rgb diffChanged) {}

  /** The default palette. */
  public static final Palette LIGHT =
      new Palette(
          false,
          Rgb.of(0x1b2e44),
          Rgb.of(0xffffff),
          Rgb.of(0xbfe6f8),
          Rgb.of(0x1b2e44),
          Rgb.of(0xf1f6fa),
          Rgb.of(0x6e7f91),
          Rgb.of(0xf3f5f8),
          Rgb.of(0xb5740f),
          Rgb.of(0xbfc9d3),
          Rgb.of(0xfdebc8),
          Rgb.of(0xe4eef7),
          Rgb.of(0xd5f0e3),
          tokens(
              TokenStyle.italic(0x5a6a7b),
              TokenStyle.plain(0x9a5600),
              TokenStyle.plain(0xa8410e),
              TokenStyle.bold(0x0a6a96),
              TokenStyle.plain(0x2c5d8c),
              TokenStyle.plain(0x18736a),
              TokenStyle.plain(0x8338a0),
              TokenStyle.plain(0x8338a0),
              TokenStyle.plain(0x1b2e44),
              TokenStyle.plain(0x45576b),
              TokenStyle.plain(0x22507a),
              TokenStyle.plain(0x7a5a00),
              TokenStyle.plain(0x0a6a96),
              TokenStyle.plain(0x8a5000),
              TokenStyle.bold(0x1b2e44),
              TokenStyle.italic(0x1b2e44),
              TokenStyle.underline(0x0a6a96),
              TokenStyle.underline(0xc0281f)),
          new Accents(
              Rgb.of(0x5a6a7b),
              Rgb.of(0xc0281f),
              Rgb.of(0x0a6a96),
              Rgb.of(0x18736a),
              Rgb.of(0xfde2e1),
              Rgb.of(0xdcf5e3),
              Rgb.of(0xfdf0cc)));

  /** The dark palette. */
  public static final Palette DARK =
      new Palette(
          true,
          Rgb.of(0xdce6f0),
          Rgb.of(0x1b2e44),
          Rgb.of(0x2f5f88),
          Rgb.of(0xffffff),
          Rgb.of(0x223a54),
          Rgb.of(0x8095aa),
          Rgb.of(0x162638),
          Rgb.of(0xf3b65b),
          Rgb.of(0x40597a),
          Rgb.of(0x463818),
          Rgb.of(0x2a3d5c),
          Rgb.of(0x1d4436),
          tokens(
              TokenStyle.italic(0x98abbf),
              TokenStyle.plain(0xf3b65b),
              TokenStyle.plain(0xf8cf8f),
              TokenStyle.bold(0x56c1f0),
              TokenStyle.plain(0x93d5f5),
              TokenStyle.plain(0x7fd8c5),
              TokenStyle.plain(0xdcaaf2),
              TokenStyle.plain(0xdcaaf2),
              TokenStyle.plain(0xdce6f0),
              TokenStyle.plain(0xb3c3d4),
              TokenStyle.plain(0xa3cdef),
              TokenStyle.plain(0xe8c872),
              TokenStyle.plain(0x56c1f0),
              TokenStyle.plain(0xf3b65b),
              TokenStyle.bold(0x56c1f0),
              TokenStyle.italic(0xdce6f0),
              TokenStyle.underline(0x56c1f0),
              TokenStyle.underline(0xff8a80)),
          new Accents(
              Rgb.of(0x98abbf),
              Rgb.of(0xff8a80),
              Rgb.of(0x56c1f0),
              Rgb.of(0x5fd3b0),
              Rgb.of(0x4a2a30),
              Rgb.of(0x1f4232),
              Rgb.of(0x4a3d1f)));

  /** Checks that every role has a style and keeps an unmodifiable copy. */
  public Palette {
    if (accents == null) {
      throw new IllegalArgumentException("null accents");
    }
    for (TokenRole role : TokenRole.values()) {
      if (tokens.get(role) == null) {
        throw new IllegalArgumentException("no style for " + role);
      }
    }
    tokens = Collections.unmodifiableMap(new EnumMap<TokenRole, TokenStyle>(tokens));
  }

  /**
   * Returns a role's style.
   *
   * @param role the role
   * @return the style
   */
  public TokenStyle style(TokenRole role) {
    return tokens.get(role);
  }

  /**
   * Returns the label of an unselected tab on the {@link #gutter} strip, dimmer than {@link
   * #selectionText} so the selected tab stays apparent while it has the strip's background.
   *
   * @return the color
   */
  public Rgb tabText() {
    return accents.tabText();
  }

  /**
   * Returns the overview strip's mark of a find-bar match.
   *
   * @return the color
   */
  public Rgb overviewFind() {
    return accents.overviewFind();
  }

  /**
   * Returns the overview strip's mark of a HyperSearch match.
   *
   * @return the color
   */
  public Rgb overviewSearch() {
    return accents.overviewSearch();
  }

  /**
   * Returns the gutter mark of a bookmark with a comment; a bookmark without one is marked in the
   * caret color.
   *
   * @return the color
   */
  public Rgb bookmarkComment() {
    return accents.bookmarkComment();
  }

  /**
   * Returns the comparison window's background of a line on the left only.
   *
   * @return the color
   */
  public Rgb diffRemoved() {
    return accents.diffRemoved();
  }

  /**
   * Returns the comparison window's background of a line on the right only.
   *
   * @return the color
   */
  public Rgb diffAdded() {
    return accents.diffAdded();
  }

  /**
   * Returns the comparison window's background of a line that differs between the sides.
   *
   * @return the color
   */
  public Rgb diffChanged() {
    return accents.diffChanged();
  }

  /** Maps the styles, given in {@link TokenRole} declaration order. */
  private static Map<TokenRole, TokenStyle> tokens(TokenStyle... styles) {
    TokenRole[] roles = TokenRole.values();
    if (styles.length != roles.length) {
      throw new IllegalArgumentException(styles.length + " styles for " + roles.length + " roles");
    }
    Map<TokenRole, TokenStyle> map = new EnumMap<TokenRole, TokenStyle>(TokenRole.class);
    for (int i = 0; i < roles.length; i++) {
      map.put(roles[i], styles[i]);
    }
    return map;
  }
}
