package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.util.ArrayList;
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
  }

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
    List<Symbol> out = new ArrayList<Symbol>();
    if (rules.isEmpty()) {
      return new Result(out, false, 0);
    }
    Tokenizer tokenizer = syntax.tokenizer(maxLineChars);
    int state = Tokenizer.INITIAL;
    int skipped = 0;
    int lineCount = text.lineCount();
    int line = 0;
    while (line < lineCount) {
      cancel.check();
      int last = Math.min(lineCount, line + BLOCK_LINES);
      int start = text.offsetOfLine(line);
      int end = last < lineCount ? text.offsetOfLine(last) : text.length();
      if (end - start > BLOCK_CHARS) {
        if (text.lineLength(line) > maxLineChars) {
          skipped++;
          line++;
          continue;
        }
        last = line + 1;
        end = start + text.lineLength(line);
      }
      String block = text.subSequence(start, end).toString();
      int from = 0;
      for (int k = line; k < last; k++) {
        int stop = from;
        while (stop < block.length() && block.charAt(stop) != '\n' && block.charAt(stop) != '\r') {
          stop++;
        }
        if (stop - from > maxLineChars) {
          skipped++;
        } else {
          String lineText = block.substring(from, stop);
          state = tokenizer.tokenize(lineText, state, tokens, cancel);
          if (find(lineText, k, out, cancel)) {
            return new Result(out, true, skipped);
          }
        }
        boolean crLf =
            stop + 1 < block.length()
                && block.charAt(stop) == '\r'
                && block.charAt(stop + 1) == '\n';
        from = stop < block.length() ? stop + (crLf ? 2 : 1) : stop;
      }
      line = last;
    }
    return new Result(out, false, skipped);
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
