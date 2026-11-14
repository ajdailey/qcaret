package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * How {@link DocumentFiles#load} opens a file.
 *
 * @param forced the charset the user chose, or null to detect one
 * @param fallback the charset detection falls back to
 * @param empty the line ending of a file without breaks
 * @param limits the undo history bounds
 * @param allowBinary true to open a binary file instead of failing
 * @param configured the EditorConfig charset, tried after a declared one and before UTF-8, or null
 * @param modelines true to read a charset from modelines past the coding line
 * @param emptyCharset the charset of a file of 0 bytes when none is forced or configured, or null
 *     to detect
 */
public record LoadOptions(
    Charset forced,
    Charset fallback,
    LineEnding empty,
    UndoLimits limits,
    boolean allowBinary,
    Charset configured,
    boolean modelines,
    Charset emptyCharset) {
  /**
   * Creates options that detect the charset of an empty file.
   *
   * @param forced the charset the user chose, or null to detect one
   * @param fallback the charset detection falls back to
   * @param empty the line ending of a file without breaks
   * @param limits the undo history bounds
   * @param allowBinary true to open a binary file instead of failing
   * @param configured the EditorConfig charset, or null
   * @param modelines true to read a charset from modelines past the coding line
   */
  public LoadOptions(
      Charset forced,
      Charset fallback,
      LineEnding empty,
      UndoLimits limits,
      boolean allowBinary,
      Charset configured,
      boolean modelines) {
    this(forced, fallback, empty, limits, allowBinary, configured, modelines, null);
  }

  /**
   * Creates options without a configured charset or modelines.
   *
   * @param forced the charset the user chose, or null to detect one
   * @param fallback the charset detection falls back to
   * @param empty the line ending of a file without breaks
   * @param limits the undo history bounds
   * @param allowBinary true to open a binary file instead of failing
   */
  public LoadOptions(
      Charset forced, Charset fallback, LineEnding empty, UndoLimits limits, boolean allowBinary) {
    this(forced, fallback, empty, limits, allowBinary, null, false);
  }

  /** Detection with a windows-1252 fallback, LF for an empty file, default undo bounds. */
  public static final LoadOptions DEFAULTS =
      new LoadOptions(
          null, Charset.forName("windows-1252"), LineEnding.LF, UndoLimits.DEFAULTS, false);

  /**
   * Returns these options with a forced charset.
   *
   * @param charset the charset, or null to detect
   * @return the options
   */
  public LoadOptions withForced(Charset charset) {
    return new LoadOptions(
        charset, fallback, empty, limits, allowBinary, configured, modelines, emptyCharset);
  }

  /**
   * Returns the options that open a binary file read-only as ISO-8859-1, which maps every byte.
   *
   * @return the options
   */
  public LoadOptions asBinary() {
    return new LoadOptions(
        StandardCharsets.ISO_8859_1,
        fallback,
        empty,
        limits,
        true,
        configured,
        modelines,
        emptyCharset);
  }

  /**
   * Returns these options with a configured charset and the modeline switch.
   *
   * @param charset the EditorConfig charset, or null
   * @param read true to read modelines
   * @return the options
   */
  public LoadOptions withDeclarations(Charset charset, boolean read) {
    return new LoadOptions(
        forced, fallback, empty, limits, allowBinary, charset, read, emptyCharset);
  }

  /**
   * Returns these options with the settings of a document that has nothing to detect from.
   *
   * @param ending the line ending of a file without breaks
   * @param charset the charset of an empty file, or null to detect
   * @return the options
   */
  public LoadOptions withNewFile(LineEnding ending, Charset charset) {
    return new LoadOptions(
        forced, fallback, ending, limits, allowBinary, configured, modelines, charset);
  }
}
