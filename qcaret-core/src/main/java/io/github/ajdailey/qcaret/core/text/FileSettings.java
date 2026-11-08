package io.github.ajdailey.qcaret.core.text;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The settings a file's EditorConfig properties and modelines declare, a modeline's replacing
 * EditorConfig's; each is null, 0 for the width or false for a flag, when neither declares it.
 *
 * <p>With {@code indent_style = space} the width is {@code indent_size}, else {@code tab_width};
 * with tabs, or no style, it is {@code tab_width}, else {@code indent_size}. A document has one
 * width for both.
 *
 * @param tabWidth columns per tab stop and indentation step, 1 to 16, or 0
 * @param insertSpaces true when indentation is spaces, false for tabs
 * @param lineEnding the line ending
 * @param encoding the declared charset and whether a new file starts with a byte order mark
 * @param trimTrailing {@code trim_trailing_whitespace = true}
 * @param finalNewline {@code insert_final_newline = true}
 * @param editorConfigs the {@code .editorconfig} files that contributed, nearest first
 * @param modeline the modeline kind ({@link Modeline#kind}), or null
 */
public record FileSettings(
    int tabWidth,
    Boolean insertSpaces,
    LineEnding lineEnding,
    TextEncoding encoding,
    boolean trimTrailing,
    boolean finalNewline,
    List<Path> editorConfigs,
    String modeline) {
  /** Nothing declared. */
  public static final FileSettings NONE =
      new FileSettings(0, null, null, null, false, false, List.of(), null);

  /**
   * Combines EditorConfig properties and a modeline.
   *
   * @param config the EditorConfig properties
   * @param modeline the modeline settings
   * @return the settings
   */
  public static FileSettings of(EditorConfig.Result config, Modeline modeline) {
    Map<String, String> p = config.properties();
    Boolean spaces = null;
    String style = p.get("indent_style");
    if ("space".equals(style)) {
      spaces = Boolean.TRUE;
    } else if ("tab".equals(style)) {
      spaces = Boolean.FALSE;
    }
    int indent = width(p.get("indent_size"));
    int tab = width(p.get("tab_width"));
    int width = Boolean.TRUE.equals(spaces) ? first(indent, tab) : first(tab, indent);
    LineEnding ending = ending(p.get("end_of_line"));
    TextEncoding encoding = encoding(p.get("charset"));
    if (modeline.tabWidth() > 0) {
      width = modeline.tabWidth();
    }
    if (modeline.insertSpaces() != null) {
      spaces = modeline.insertSpaces();
    }
    if (modeline.lineEnding() != null) {
      ending = modeline.lineEnding();
    }
    if (modeline.charset() != null) {
      encoding = TextEncoding.of(modeline.charset(), false);
    }
    return new FileSettings(
        width,
        spaces,
        ending,
        encoding,
        "true".equals(p.get("trim_trailing_whitespace")),
        "true".equals(p.get("insert_final_newline")),
        config.files(),
        modeline.kind());
  }

  /**
   * Reports whether anything is declared or any source was found.
   *
   * @return false for {@link #NONE} and its equals
   */
  public boolean any() {
    return !equals(NONE);
  }

  /**
   * Returns the charset an EditorConfig {@code charset} value names.
   *
   * @param value {@code latin1}, {@code utf-8}, {@code utf-8-bom}, {@code utf-16be}, {@code
   *     utf-16le}, or another value
   * @return the encoding, or null for another value
   */
  public static TextEncoding encoding(String value) {
    if (value == null) {
      return null;
    }
    return switch (value) {
      case "latin1" -> TextEncoding.of(StandardCharsets.ISO_8859_1, false);
      case "utf-8" -> TextEncoding.of(StandardCharsets.UTF_8, false);
      case "utf-8-bom" -> TextEncoding.of(StandardCharsets.UTF_8, true);
      case "utf-16be" -> TextEncoding.of(StandardCharsets.UTF_16BE, true);
      case "utf-16le" -> TextEncoding.of(StandardCharsets.UTF_16LE, true);
      default -> null;
    };
  }

  /**
   * Returns the charset a load tries before UTF-8 and the fallback: the declared one, unless it is
   * UTF-16 or UTF-32, which detection finds by its byte order mark or zero bytes.
   *
   * @return the charset, or null
   */
  public Charset declaredCharset() {
    if (encoding == null || encoding.unitWidth() > 1) {
      return null;
    }
    return encoding.charset();
  }

  private static int width(String value) {
    if (value == null) {
      return 0;
    }
    try {
      int n = Integer.parseInt(value);
      return n >= 1 && n <= 16 ? n : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int first(int a, int b) {
    return a > 0 ? a : b;
  }

  private static LineEnding ending(String value) {
    if (value == null) {
      return null;
    }
    return switch (value) {
      case "lf" -> LineEnding.LF;
      case "crlf" -> LineEnding.CRLF;
      case "cr" -> LineEnding.CR;
      default -> null;
    };
  }
}
