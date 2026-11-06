package io.github.ajdailey.qcaret.app.print;

import io.github.ajdailey.qcaret.app.editor.TokenCache;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.syntax.TokenList;
import io.github.ajdailey.qcaret.core.syntax.Tokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pages of a {@link PrintSource} on a page of fixed columns and rows. {@link #of} walks the
 * text once and keeps only where each page starts; {@link #page} builds a page's rows on demand, so
 * memory follows the page count and not the text. Confined to one worker.
 *
 * <ul>
 *   <li>A line wider than the text columns wraps by column; a continuation row has no number. A
 *       wide char that does not fit moves to the next row.
 *   <li>A tab expands to the next stop of its row, cut at the row's end. Stops restart on each row,
 *       and a selection starting inside a line starts a row.
 *   <li>The empty line after a final line break prints no row, so a text that fills its last page
 *       exactly adds no blank page. An empty text prints one empty row.
 * </ul>
 */
public final class PrintLayout {
  private static final TokenRole[] ROLES = TokenRole.values();
  private static final int[] NO_TOKENS = new int[0];

  private final PrintSource source;
  private final TextSnapshot text;
  private final int[] ranges;
  private final int rowsPerPage;
  private final int gutter;
  private final int width;
  private final LineView view = new LineView();
  private final LineView tokenView = new LineView();
  private final TokenList tokenList = new TokenList();
  private final Tokenizer tokenizer;
  private int[] pageRange = new int[64];
  private int[] pageLine = new int[64];
  private int[] pageIndex = new int[64];
  private int pages;
  private int tokenLine = -1;
  private int tokenState = Tokenizer.INITIAL;
  private int[] lineTokens = NO_TOKENS;

  private PrintLayout(PrintSource source, int[] ranges, PrintTarget.Frame frame) {
    this.source = source;
    this.text = source.text();
    this.ranges = ranges;
    this.rowsPerPage = Math.max(1, frame.rows());
    int last = text.lineAt(ranges[ranges.length - 1]) + 1;
    this.gutter = source.lineNumbers() ? Integer.toString(last).length() + 1 : 0;
    this.width = Math.max(1, frame.columns() - gutter);
    this.tokenizer =
        source.syntax() == null ? null : source.syntax().tokenizer(source.maxLineChars());
  }

  /**
   * Paginates a source.
   *
   * @param source the text
   * @param selectionOnly true for the source's selections, which must not be empty
   * @param frame the columns and rows of a page
   * @param cancel checked per line
   * @return the layout, at least one page
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static PrintLayout of(
      PrintSource source,
      boolean selectionOnly,
      PrintTarget.Frame frame,
      CancellationToken cancel) {
    int[] ranges = selectionOnly ? source.selections() : new int[] {0, source.text().length()};
    PrintLayout layout = new PrintLayout(source, ranges, frame);
    layout.paginate(cancel);
    return layout;
  }

  private void paginate(CancellationToken cancel) {
    Walk walk = new Walk();
    boolean more = walk.seek(0, text.lineAt(ranges[0]));
    addPage(walk.range, walk.line, walk.from);
    int rows = 0;
    while (more) {
      cancel.check();
      text.lineView(walk.line, view);
      int at = walk.from;
      do {
        if (rows == rowsPerPage) {
          addPage(walk.range, walk.line, at);
          rows = 0;
        }
        at = fill(view, at, walk.to, null);
        rows++;
      } while (at < walk.to);
      more = walk.next();
    }
  }

  private void addPage(int range, int line, int index) {
    if (pages == pageLine.length) {
      pageRange = Arrays.copyOf(pageRange, pages * 2);
      pageLine = Arrays.copyOf(pageLine, pages * 2);
      pageIndex = Arrays.copyOf(pageIndex, pages * 2);
    }
    pageRange[pages] = range;
    pageLine[pages] = line;
    pageIndex[pages] = index;
    pages++;
  }

  /**
   * Returns the page count.
   *
   * @return at least 1
   */
  public int pageCount() {
    return pages;
  }

  /**
   * Builds a page. Tokens are computed forward from the first line, so numbers must ascend.
   *
   * @param number the page, from 1 to {@link #pageCount}
   * @param cancel checked per line
   * @return the page
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public PrintTarget.Page page(int number, CancellationToken cancel) {
    Walk walk = new Walk();
    boolean more = walk.seek(pageRange[number - 1], pageLine[number - 1]);
    int at = pageIndex[number - 1];
    List<PrintTarget.Row> rows = new ArrayList<PrintTarget.Row>(rowsPerPage);
    while (more && rows.size() < rowsPerPage) {
      cancel.check();
      text.lineView(walk.line, view);
      int[] tokens = tokens(walk.line, cancel);
      do {
        RowText row = new RowText(tokens);
        int shown = source.lineNumbers() && at == walk.from ? walk.line + 1 : 0;
        at = fill(view, at, walk.to, row);
        rows.add(new PrintTarget.Row(shown, row.spans()));
      } while (at < walk.to && rows.size() < rowsPerPage);
      if (at >= walk.to) {
        more = walk.next();
        at = walk.from;
      }
    }
    return new PrintTarget.Page(number, pages, gutter, rows);
  }

  /** Returns a line's {@code start, end, role} tokens, tokenizing forward to it. */
  @WorkerThread
  private int[] tokens(int line, CancellationToken cancel) {
    if (tokenizer == null) {
      return NO_TOKENS;
    }
    if (line != tokenLine) {
      for (int next = tokenLine + 1; next <= line; next++) {
        LineView v = text.lineView(next, tokenView);
        CharSequence chars = v.length() > tokenizer.maxLineChars() ? v : v.toString();
        tokenState = tokenizer.tokenize(chars, tokenState, tokenList, cancel);
      }
      tokenLine = line;
      lineTokens = TokenCache.toRoles(tokenList.toArray());
    }
    return lineTokens;
  }

  /**
   * Lays one row out from {@code from}.
   *
   * @param out receives the row, or null to measure
   * @return the index after the row's last char
   */
  private int fill(LineView line, int from, int to, RowText out) {
    int column = 0;
    int i = from;
    while (i < to) {
      char c = line.charAt(i);
      if (c == '\t') {
        if (column >= width) {
          break;
        }
        int tab = source.tabWidth();
        int stop = Math.min(width, column + tab - column % tab);
        if (out != null) {
          out.add(i, column, " ".repeat(stop - column));
        }
        column = stop;
        i++;
        continue;
      }
      int count = 1;
      int point = c;
      if (Character.isHighSurrogate(c)
          && i + 1 < to
          && Character.isLowSurrogate(line.charAt(i + 1))) {
        point = Character.toCodePoint(c, line.charAt(i + 1));
        count = 2;
      }
      int advance = Columns.width(point);
      if (column > 0 && column + advance > width) {
        break;
      }
      if (out != null) {
        out.add(i, column, Character.toString(point));
      }
      column += advance;
      i += count;
    }
    return i;
  }

  /** The part of each line the ranges hold, in order. */
  private final class Walk {
    int range;
    int line;
    int from;
    int to;

    /** Moves to a line of a range; false when that line holds nothing to print. */
    boolean seek(int atRange, int atLine) {
      range = atRange;
      line = atLine;
      int start = ranges[range * 2];
      int end = ranges[range * 2 + 1];
      int lineStart = text.offsetOfLine(line);
      from = Math.max(0, start - lineStart);
      to = Math.min(text.lineLength(line), end - lineStart);
      return lineStart < end || start == end;
    }

    /** Moves to the next part; false after the last. */
    boolean next() {
      int nextRange = range;
      int nextLine = line + 1;
      while (true) {
        if (nextLine <= text.lineAt(ranges[nextRange * 2 + 1]) && seek(nextRange, nextLine)) {
          return true;
        }
        nextRange++;
        if (nextRange * 2 == ranges.length) {
          return false;
        }
        nextLine = text.lineAt(ranges[nextRange * 2]);
      }
    }
  }

  /** The spans of one row: runs of one role. */
  private static final class RowText {
    private final int[] tokens;
    private final List<PrintTarget.Span> spans = new ArrayList<PrintTarget.Span>();
    private final StringBuilder run = new StringBuilder();
    private TokenRole role;
    private int runColumn;
    private int token;

    RowText(int[] tokens) {
      this.tokens = tokens;
    }

    void add(int index, int column, String chars) {
      TokenRole at = roleAt(index);
      if (at != role) {
        flush();
        role = at;
      }
      if (run.length() == 0) {
        runColumn = column;
      }
      run.append(chars);
    }

    private TokenRole roleAt(int index) {
      while (token < tokens.length && tokens[token + 1] <= index) {
        token += 3;
      }
      return token < tokens.length && tokens[token] <= index ? ROLES[tokens[token + 2]] : null;
    }

    private void flush() {
      if (run.length() > 0) {
        spans.add(new PrintTarget.Span(run.toString(), runColumn, role));
        run.setLength(0);
      }
    }

    List<PrintTarget.Span> spans() {
      flush();
      return spans;
    }
  }
}
