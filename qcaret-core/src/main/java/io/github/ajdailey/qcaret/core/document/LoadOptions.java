package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * How {@link DocumentFiles#load} opens a file.
 *
 * @param forced the charset the user chose, or null to detect one
 * @param fallback the charset detection falls back to ({@code encoding.fallback})
 * @param empty the line ending of a file without breaks ({@code newFile.lineEnding})
 * @param limits the undo history bounds
 * @param allowBinary true to open a binary file instead of failing
 */
public record LoadOptions(
    Charset forced, Charset fallback, LineEnding empty, UndoLimits limits, boolean allowBinary) {
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
    return new LoadOptions(charset, fallback, empty, limits, allowBinary);
  }

  /**
   * Returns the options that open a binary file read-only as ISO-8859-1, which maps every byte.
   *
   * @return the options
   */
  public LoadOptions asBinary() {
    return new LoadOptions(StandardCharsets.ISO_8859_1, fallback, empty, limits, true);
  }
}
