package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Finds a document's definitions with its grammar's {@link Grammar.SymbolRule}s. Every line is
 * tokenized from the first, so a rule's match counts only when its first non-blank char lies
 * outside comments and strings; a name group may lie in a string, as HCL's quoted labels do. Each
 * rule runs over each line through a {@link BoundedChars}; a run that reaches its read limit or
 * overflows the stack finds nothing more on that line. Lines over the length limit are skipped and
 * keep the tokenizer's state. Not thread-safe; one scan per instance at a time.
 */
public final class SymbolScanner {
  /** Default count at which a scan stops. */
  public static final int MAX_SYMBOLS = 10_000;

  /** Longest name kept; a longer one is cut. */
  static final int MAX_NAME_CHARS = 200;

  private static final int BLOCK_LINES = 1024;
  private static final int BLOCK_CHARS = 1 << 20;

  /**
   * One definition.
   *
   * @param name the name group's text
   * @param kind what the rule says it defines
   * @param line the zero-based line
   * @param column the name's char index in the line
   */
  public record Symbol(String name, SymbolKind kind, int line, int column) {}

  /**
   * A scan's output.
   *
   * @param symbols by line, then column
   * @param truncated true when the scan stopped at its count
   * @param skippedLines lines over the length limit, not scanned
   */
  public record Result(List<Symbol> symbols, boolean truncated, int skippedLines) {
    /** Copies the list. */
    public Result {
      symbols = List.copyOf(symbols);
    }

    /**
     * Returns the heap the result is charged: {@value #SYMBOL_BYTES} bytes per symbol plus two per
     * name char.
     *
     * @return bytes
     */
    public long bytes() {
      long bytes = 0;
      for (Symbol s : symbols) {
        bytes += SYMBOL_BYTES + 2L * s.name().length();
      }
      return bytes;
    }
  }

  /** Heap charged per symbol besides its name's chars: the record, the string and the slot. */
  static final int SYMBOL_BYTES = 80;

  private final Syntax syntax;
  private final int maxLineChars;
  private final int maxSymbols;
  private final List<Grammar.SymbolRule> rules;
  private final BoundedChars chars = new BoundedChars();
  private final Matcher[] matchers;
  private final TokenList tokens = new TokenList();

  /**
   * Creates a scanner for a grammar.
   *
   * @param syntax the compiled grammar
   * @param maxLineChars lines longer than this are skipped
   * @param maxSymbols the count at which a scan stops
   */
  public SymbolScanner(Syntax syntax, int maxLineChars, int maxSymbols) {
    this.syntax = syntax;
    this.maxLineChars = maxLineChars;
    this.maxSymbols = maxSymbols;
    this.rules = syntax.grammar().symbols();
    this.matchers = new Matcher[rules.size()];
    for (int i = 0; i < matchers.length; i++) {
      matchers[i] = rules.get(i).regex().matcher(chars);
    }
  }

  /**
   * Scans a text.
   *
   * @param text the text
   * @param cancel checked per block of lines and while matching
   * @return the definitions
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public Result scan(TextContent text, CancellationToken cancel) {
    return scanAll(text, cancel).result();
  }

  /**
   * Scans a text from its first line.
   *
   * @param text the text
   * @param cancel checked per block of lines and while matching
   * @return the definitions and the states a {@link #rescan} resumes from
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public SymbolScan scanAll(TextContent text, CancellationToken cancel) {
    if (rules.isEmpty()) {
      return new SymbolScan(
          new Result(List.<Symbol>of(), false, 0),
          syntax,
          null,
          text.lineCount(),
          text.lineCount(),
          new int[] {0},
          new int[] {Tokenizer.INITIAL},
          new int[0]);
    }
    Run run = new Run(syntax.tokenizer(maxLineChars));
    run.scan(text, 0, Tokenizer.INITIAL, null, Integer.MAX_VALUE, 0, cancel);
    return run.finish(text.lineCount());
  }

  /**
   * Scans an edited text from the last checkpoint of {@code previous} at or before the line above
   * the first changed line. Past the changed lines, at a checkpoint where the tokenizer state
   * equals the one {@code previous} recorded, the rest of {@code previous} is taken with its lines
   * shifted, unless that would reach the count. A truncated {@code previous} is never taken: the
   * scan reads on to the end or the count, or, when every changed line follows the line it stopped
   * in, is returned as it is. The result equals {@link #scanAll} of the text. A {@code previous}
   * from another grammar scans from the first line.
   *
   * @param text the text after the edits
   * @param previous the scan of the text before them, consumed
   * @param prefixChars chars at the start the edits left unchanged
   * @param suffixChars chars at the end the edits left unchanged
   * @param cancel checked per block of lines and while matching
   * @return the definitions and the states a later rescan resumes from
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public SymbolScan rescan(
      TextContent text,
      SymbolScan previous,
      int prefixChars,
      int suffixChars,
      CancellationToken cancel) {
    if (rules.isEmpty() || previous.syntax != syntax || previous.tokenizer == null) {
      return scanAll(text, cancel);
    }
    int length = text.length();
    int lineCount = text.lineCount();
    int changed = Math.max(0, text.lineAt(Math.min(Math.max(0, prefixChars), length)) - 1);
    Result old = previous.result();
    if (old.truncated() && previous.stop < changed) {
      return new SymbolScan(
          old,
          syntax,
          previous.tokenizer,
          lineCount,
          previous.stop,
          previous.lines,
          previous.states,
          previous.skipped);
    }
    int checkpoint = floor(previous.lines, changed);
    int from = previous.lines[checkpoint];
    Run run = new Run(previous.tokenizer);
    for (int i = 0; i <= checkpoint; i++) {
      run.checkpoint(previous.lines[i], previous.states[i]);
    }
    for (int s : previous.skipped) {
      if (s < from) {
        run.skipped.add(s);
      }
    }
    for (Symbol s : old.symbols()) {
      if (s.line() >= from) {
        break;
      }
      run.out.add(s);
    }
    int suffixStart =
        suffixChars <= 0 || suffixChars > length || old.truncated()
            ? Integer.MAX_VALUE
            : text.lineAt(length - suffixChars) + 1;
    run.scan(
        text,
        from,
        previous.states[checkpoint],
        previous,
        suffixStart,
        lineCount - previous.lineCount,
        cancel);
    return run.finish(lineCount);
  }

  /** Returns the index of the last ascending value at or below a key; 0 when none is. */
  private static int floor(int[] values, int key) {
    int low = 0;
    int high = values.length - 1;
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (values[mid] <= key) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  /** One scan's growing output. */
  private final class Run {
    final Tokenizer tokenizer;
    final List<Symbol> out = new ArrayList<Symbol>();
    final IntList skipped = new IntList();
    final IntList lines = new IntList();
    final IntList states = new IntList();
    boolean truncated;
    int stop;

    Run(Tokenizer tokenizer) {
      this.tokenizer = tokenizer;
    }

    void checkpoint(int line, int state) {
      if (lines.size == 0 || line - lines.values[lines.size - 1] >= BLOCK_LINES) {
        lines.add(line);
        states.add(state);
      }
    }

    /**
     * Scans from a line in a state to the end or the count, or until it takes the rest of {@code
     * previous}.
     *
     * @param previous the scan to take the rest of, or null
     * @param suffixStart the first line of the unchanged end, where taking can start
     * @param delta this text's line count minus the previous text's
     */
    @WorkerThread
    void scan(
        TextContent text,
        int line,
        int state,
        SymbolScan previous,
        int suffixStart,
        int delta,
        CancellationToken cancel) {
      int lineCount = text.lineCount();
      while (line < lineCount) {
        cancel.check();
        int last = Math.min(lineCount, line + BLOCK_LINES);
        int start = text.offsetOfLine(line);
        int end = last < lineCount ? text.offsetOfLine(last) : text.length();
        String block = null;
        if (end - start > BLOCK_CHARS) {
          last = line + 1;
          if (text.lineLength(line) <= maxLineChars) {
            block = text.subSequence(start, start + text.lineLength(line)).toString();
          }
        } else {
          block = text.subSequence(start, end).toString();
        }
        int from = 0;
        for (int k = line; k < last; k++) {
          if (k >= suffixStart && take(previous, k, state, delta)) {
            return;
          }
          checkpoint(k, state);
          if (block == null) {
            skipped.add(k);
            continue;
          }
          int to = from;
          while (to < block.length() && block.charAt(to) != '\n' && block.charAt(to) != '\r') {
            to++;
          }
          if (to - from > maxLineChars) {
            skipped.add(k);
          } else {
            String lineText = block.substring(from, to);
            state = tokenizer.tokenize(lineText, state, tokens, cancel);
            if (find(lineText, k, out, cancel)) {
              truncated = true;
              stop = k;
              return;
            }
          }
          boolean crLf =
              to + 1 < block.length() && block.charAt(to) == '\r' && block.charAt(to + 1) == '\n';
          from = to < block.length() ? to + (crLf ? 2 : 1) : to;
        }
        line = last;
      }
    }

    /**
     * Takes the rest of {@code previous} from the line matching {@code line} when it has a
     * checkpoint there in {@code state} and its symbols from there stay under the count.
     */
    private boolean take(SymbolScan previous, int line, int state, int delta) {
      int old = line - delta;
      int i = floor(previous.lines, old);
      if (previous.lines[i] != old || previous.states[i] != state) {
        return false;
      }
      List<Symbol> rest = previous.result().symbols();
      int first = rest.size();
      while (first > 0 && rest.get(first - 1).line() >= old) {
        first--;
      }
      if (out.size() + rest.size() - first >= maxSymbols) {
        return false;
      }
      for (int j = first; j < rest.size(); j++) {
        Symbol s = rest.get(j);
        out.add(new Symbol(s.name(), s.kind(), s.line() + delta, s.column()));
      }
      for (int s : previous.skipped) {
        if (s >= old) {
          skipped.add(s + delta);
        }
      }
      for (int j = i; j < previous.lines.length; j++) {
        lines.add(previous.lines[j] + delta);
        states.add(previous.states[j]);
      }
      return true;
    }

    SymbolScan finish(int lineCount) {
      return new SymbolScan(
          new Result(out, truncated, skipped.size),
          syntax,
          tokenizer,
          lineCount,
          truncated ? stop : lineCount,
          lines.toArray(),
          states.toArray(),
          skipped.toArray());
    }
  }

  /** A growing int array. */
  private static final class IntList {
    int[] values = new int[16];
    int size;

    void add(int value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = value;
    }

    int[] toArray() {
      return Arrays.copyOf(values, size);
    }
  }

  /**
   * Adds the line's definitions by column, one per column, the first rule winning.
   *
   * @return true when the count is reached
   */
  private boolean find(String lineText, int line, List<Symbol> out, CancellationToken cancel) {
    int first = out.size();
    for (int i = 0; i < matchers.length; i++) {
      Grammar.SymbolRule rule = rules.get(i);
      Matcher m = matchers[i];
      chars.reset(lineText, BoundedChars.lineLimit(lineText), cancel);
      m.reset();
      try {
        int at = 0;
        while (at <= lineText.length() && m.find(at)) {
          int name = start(m, rule.group());
          if (name >= 0
              && end(m, rule.group()) > name
              && code(tokens.typeAt(firstNonBlank(lineText, m.start(), m.end())))
              && !taken(out, first, name)) {
            String text = lineText.substring(name, end(m, rule.group()));
            if (text.length() > MAX_NAME_CHARS) {
              text = text.substring(0, MAX_NAME_CHARS);
            }
            insert(out, first, new Symbol(text, rule.kind(), line, name));
            if (out.size() >= maxSymbols) {
              return true;
            }
          }
          at = Math.max(m.end(), m.start() + 1);
        }
      } catch (BoundedChars.LimitReached | StackOverflowError e) {
        // The rest of the line has no match for this rule.
      }
    }
    return false;
  }

  private static int firstNonBlank(String line, int from, int to) {
    int at = from;
    while (at < to - 1 && Character.isWhitespace(line.charAt(at))) {
      at++;
    }
    return at;
  }

  private static int start(Matcher m, String group) {
    return Character.isDigit(group.charAt(0)) ? m.start(Integer.parseInt(group)) : m.start(group);
  }

  private static int end(Matcher m, String group) {
    return Character.isDigit(group.charAt(0)) ? m.end(Integer.parseInt(group)) : m.end(group);
  }

  private static boolean taken(List<Symbol> out, int first, int column) {
    for (int i = first; i < out.size(); i++) {
      if (out.get(i).column() == column) {
        return true;
      }
    }
    return false;
  }

  /** Inserts a line's symbol in column order among the line's symbols from {@code first}. */
  private static void insert(List<Symbol> out, int first, Symbol symbol) {
    int at = out.size();
    while (at > first && out.get(at - 1).column() > symbol.column()) {
      at--;
    }
    out.add(at, symbol);
  }

  private static boolean code(TokenType type) {
    return type != TokenType.COMMENT && type != TokenType.STRING && type != TokenType.ESCAPE;
  }
}
