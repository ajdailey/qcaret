package io.github.ajdailey.qcaret.core.syntax;

/**
 * A finished {@link SymbolScanner} scan and what {@link SymbolScanner#rescan} needs to resume it
 * after an edit: the tokenizer state at the start of a line every {@code BLOCK_LINES} lines or
 * more, and the skipped lines. It holds the scan's {@link Tokenizer}, whose state ids only that
 * tokenizer reads, so a rescan consumes it: resume a scan at most once.
 */
public final class SymbolScan {
  /** Heap charged for the tokenizer: its interned states, matchers and buffers. */
  static final int TOKENIZER_BYTES = 16 * 1024;

  private final SymbolScanner.Result result;
  final Syntax syntax;
  final Tokenizer tokenizer;
  final int lineCount;
  final int stop;
  final int[] lines;
  final int[] states;
  final int[] skipped;

  /**
   * Creates a scan.
   *
   * @param result the symbols
   * @param syntax the grammar scanned with
   * @param tokenizer the tokenizer whose states {@code states} holds, or null when there are none
   * @param lineCount the scanned text's line count
   * @param stop the line the scan stopped in when truncated, else {@code lineCount}
   * @param lines checkpoint lines, ascending, the first 0
   * @param states the tokenizer state at the start of each checkpoint line
   * @param skipped the lines over the length limit, ascending
   */
  SymbolScan(
      SymbolScanner.Result result,
      Syntax syntax,
      Tokenizer tokenizer,
      int lineCount,
      int stop,
      int[] lines,
      int[] states,
      int[] skipped) {
    this.result = result;
    this.syntax = syntax;
    this.tokenizer = tokenizer;
    this.lineCount = lineCount;
    this.stop = stop;
    this.lines = lines;
    this.states = states;
    this.skipped = skipped;
  }

  /**
   * Returns the symbols.
   *
   * @return the result
   */
  public SymbolScanner.Result result() {
    return result;
  }

  /**
   * Returns the heap the scan is charged: the result's {@link SymbolScanner.Result#bytes}, eight
   * bytes per checkpoint, four per skipped line and {@value #TOKENIZER_BYTES} for the tokenizer.
   *
   * @return bytes
   */
  public long bytes() {
    return result.bytes()
        + 8L * lines.length
        + 4L * skipped.length
        + (tokenizer == null ? 0 : TOKENIZER_BYTES);
  }
}
