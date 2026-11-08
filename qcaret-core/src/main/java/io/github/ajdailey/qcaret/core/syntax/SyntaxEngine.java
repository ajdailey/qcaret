package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Incremental highlighting of one document: the start state and fold counts of every line, kept
 * across edits, and the tokens of the lines the views show. Confined to one thread at a time; the
 * caller runs it on a worker and hands it each change in order.
 *
 * <ul>
 *   <li>Each line's start state is an interned int, 4 bytes per line; each line's fold counts
 *       another 4. The lines before the frontier hold correct values.
 *   <li>{@link #edit} shifts both arrays by each edit's line delta and moves the frontier back to
 *       the first edited line. {@link #run} tokenizes from the frontier; when a line after the last
 *       edited one, and after the frontier the edit moved back, ends in the state its successor
 *       already holds, the edit has converged and the frontier moves to where the values were
 *       correct before the edit.
 *   <li>Lines {@link #request}ed are tokenized and returned by the next run, from the stored state
 *       where the frontier has passed or one is stored, else from {@link Tokenizer#INITIAL}. The
 *       frontier returns the lines of each view range ({@link #setViewport}) again as it passes
 *       them.
 *   <li>Once the frontier reaches the end, the fold regions are computed from the stored counts and
 *       states and merged by {@link FoldRegions#merge}: those of the grammar's {@link
 *       Grammar.FoldMode#BRACKETS}, {@link Grammar.FoldMode#MARKERS} or {@link
 *       Grammar.FoldMode#SECTIONS} mode, each folding span covering two or more lines, and the
 *       grammar's region markers, paired by nesting.
 *   <li>A section header or a region marker matches at a line's start, on a line that does not
 *       start inside a span, where the token at its first non-blank char is not a comment, string
 *       or escape; a region marker may be a comment.
 * </ul>
 */
public final class SyntaxEngine {
  /** Default line length above which a line stays plain. */
  public static final int DEFAULT_MAX_LINE_CHARS = 10_000;

  private static final int OPEN_MASK = 0x7fff;
  private static final int CLOSE_SHIFT = 15;
  private static final int OVERLONG = 1 << 30;
  private static final int BLOCK_LINES = 256;
  private static final int BLOCK_CHARS = 1 << 18;
  private static final int LEVEL_MASK = 0x3f;
  private static final int PREVIOUS = 1 << 6;
  private static final int BLANK = 1 << 7;

  private final Tokenizer tokenizer;
  private final Grammar.Folding folding;
  private final TokenList tokens = new TokenList();
  private final LineView view = new LineView();
  private final boolean[] brackets = new boolean[128];
  private String lineText = "";
  private int lineLength;
  private String block = "";
  private int blockFirst = -1;
  private int blockLines;
  private final int[] blockBounds = new int[BLOCK_LINES * 2];
  private final BoundedChars markerChars = new BoundedChars();
  private final Matcher markerBegin;
  private final Matcher markerEnd;
  private final Matcher[] sections;
  private final Matcher regionBegin;
  private final Matcher regionEnd;
  private final Map<Integer, int[]> viewports = new LinkedHashMap<Integer, int[]>();
  private TextSnapshot text;
  private int lineCount;
  private int[] states;
  private int[] folds;

  /** Region marker counts per line, packed as {@link #folds}; null without region markers. */
  private int[] marks;

  private int frontier;
  private int known;
  private int dirtyEnd = -1;
  private final List<int[]> requests = new ArrayList<int[]>();
  private boolean regionsDone;

  /**
   * One run's output: the tokens of view lines, the lines the frontier tokenized, and on reaching
   * the end the fold regions.
   *
   * @param lines the lines whose tokens follow, in the current text
   * @param tokens per line, {@code start, end, type ordinal} triples
   * @param from the first line the frontier tokenized, or -1
   * @param to the line after the last one the frontier tokenized
   * @param regions the fold regions as {@code {startLine, endLine}}, ascending; null unless the
   *     frontier reached the end in this run
   * @param overLong lines over the length limit, counted with {@code regions}
   * @param done true when nothing is left to do
   */
  public record Slice(
      int[] lines,
      int[][] tokens,
      int from,
      int to,
      List<int[]> regions,
      int overLong,
      boolean done) {
    /**
     * Reports whether the run produced anything.
     *
     * @return false when it returned no line, tokenized none and found no regions
     */
    public boolean isEmpty() {
      return lines.length == 0 && from < 0 && regions == null;
    }
  }

  /**
   * Creates the engine with every line still to tokenize.
   *
   * @param syntax the compiled grammar
   * @param text the document's text
   * @param maxLineChars lines longer than this stay plain
   */
  public SyntaxEngine(Syntax syntax, TextSnapshot text, int maxLineChars) {
    this.tokenizer = syntax.tokenizer(maxLineChars);
    this.folding = syntax.grammar().folding();
    this.text = text;
    this.lineCount = text.lineCount();
    this.states = new int[lineCount + 1];
    this.folds = new int[lineCount + 1];
    markerBegin =
        folding.mode() == Grammar.FoldMode.MARKERS ? folding.begin().matcher(markerChars) : null;
    markerEnd =
        folding.mode() == Grammar.FoldMode.MARKERS ? folding.end().matcher(markerChars) : null;
    sections = new Matcher[folding.sections().size()];
    for (int i = 0; i < sections.length; i++) {
      sections[i] = folding.sections().get(i).regex().matcher(markerChars);
    }
    regionBegin = folding.regionBegin() == null ? null : folding.regionBegin().matcher(markerChars);
    regionEnd = folding.regionEnd() == null ? null : folding.regionEnd().matcher(markerChars);
    if (regionBegin != null && regionEnd != null) {
      marks = new int[lineCount + 1];
    }
    for (char c : (folding.open() + folding.close()).toCharArray()) {
      if (c < brackets.length) {
        brackets[c] = true;
      }
    }
  }

  /**
   * Returns the text the engine reads.
   *
   * @return the text of the last {@link #edit}, or the initial text
   */
  public TextSnapshot text() {
    return text;
  }

  /**
   * Returns the first line whose start state or fold counts are not yet known to be correct.
   *
   * @return the frontier, the line count when every line is done
   */
  public int frontier() {
    return frontier;
  }

  /**
   * Applies a document change: shifts the stored lines and moves the frontier back. Each edit
   * replaces its lines widened by one on each side, so a CR and an LF that join or part across the
   * edit's ends stay inside the replaced lines.
   *
   * @param edits the change's edits, ascending, in the coordinates of the text before it
   * @param after the text after the change
   */
  public void edit(List<TextEdit> edits, TextSnapshot after) {
    List<int[]> regions = new ArrayList<int[]>();
    int shift = 0;
    int lineShift = 0;
    int oldLast = text.lineCount() - 1;
    int newLast = after.lineCount() - 1;
    for (TextEdit e : edits) {
      int oldEnd = e.offset() + e.removed().length();
      int newStart = e.offset() + shift;
      int newEnd = newStart + e.inserted().length();
      // Line first starts before the offset, so only earlier edits move it; lineAt at the offset
      // itself changes when the edit parts or joins a CR and an LF there.
      int first = Math.max(0, text.lineAt(e.offset()) - 1);
      int oldEndLine = text.lineAt(oldEnd);
      int newEndLine = after.lineAt(newEnd);
      int[] region = {
        first,
        Math.min(oldLast, oldEndLine + 1),
        first + lineShift,
        Math.min(newLast, newEndLine + 1)
      };
      lineShift = newEndLine - oldEndLine;
      int[] previous = regions.isEmpty() ? null : regions.get(regions.size() - 1);
      if (previous != null && region[0] <= previous[1] + 1) {
        previous[1] = Math.max(previous[1], region[1]);
        previous[3] = Math.max(previous[3], region[3]);
      } else {
        regions.add(region);
      }
      shift += e.inserted().length() - e.removed().length();
    }
    for (int i = regions.size() - 1; i >= 0; i--) {
      int[] r = regions.get(i);
      int delta = (r[3] - r[2]) - (r[1] - r[0]);
      int from = r[1] + 1;
      ensure(lineCount + delta + 1);
      if (from <= lineCount) {
        System.arraycopy(states, from, states, from + delta, lineCount + 1 - from);
        System.arraycopy(folds, from, folds, from + delta, lineCount + 1 - from);
        if (marks != null) {
          System.arraycopy(marks, from, marks, from + delta, lineCount + 1 - from);
        }
      }
      lineCount += delta;
      if (frontier < known) {
        // States past a stopped frontier predate the edits since; converging before it keeps them.
        dirtyEnd = Math.max(dirtyEnd, frontier);
      }
      frontier = Math.min(frontier, r[0]);
      known = shift(known, r[0], r[1], delta);
      dirtyEnd = shift(dirtyEnd, r[0], r[1], delta);
    }
    for (int[] r : regions) {
      dirtyEnd = Math.max(dirtyEnd, r[3]);
    }
    text = after;
    blockFirst = -1;
    blockLines = 0;
    lineCount = after.lineCount();
    known = Math.min(known, lineCount);
    regionsDone = false;
  }

  /** Maps a line through a replaced line range; a line inside it goes to the range's start. */
  private static int shift(int line, int first, int last, int delta) {
    if (line > last) {
      return line + delta;
    }
    return Math.min(line, first);
  }

  /**
   * Sets the lines one view shows; the frontier returns them as it passes them.
   *
   * @param key identifies the view
   * @param from the first line
   * @param to the last line, inclusive
   */
  public void setViewport(int key, int from, int to) {
    viewports.put(key, new int[] {from, to});
  }

  /**
   * Forgets a view's lines.
   *
   * @param key the view
   */
  public void removeViewport(int key) {
    viewports.remove(key);
  }

  /**
   * Asks for the tokens of a line range in the next {@link #run}.
   *
   * @param from the first line
   * @param to the last line, inclusive
   */
  public void request(int from, int to) {
    requests.add(new int[] {from, to});
  }

  /**
   * Reports whether a {@link #run} has nothing to do.
   *
   * @return true when every line is done, the regions are out and no lines are requested
   */
  public boolean idle() {
    return frontier >= lineCount && regionsDone && requests.isEmpty();
  }

  /**
   * Returns the requested lines, then tokenizes from the frontier until the deadline passes, the
   * frontier passes the last view line it had not reached, or the end; at the end computes the fold
   * regions.
   *
   * @param deadline {@link System#nanoTime} to stop at
   * @param cancel checked while reading
   * @return the output
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public Slice run(long deadline, CancellationToken cancel) {
    return run(deadline, Integer.MAX_VALUE, cancel);
  }

  /**
   * As {@link #run(long, CancellationToken)}, also stopping once the frontier has tokenized {@code
   * maxLines} lines.
   *
   * @param deadline {@link System#nanoTime} to stop at
   * @param maxLines the most lines the frontier tokenizes, at least 1
   * @param cancel checked while reading
   * @return the output
   */
  @WorkerThread
  Slice run(long deadline, int maxLines, CancellationToken cancel) {
    List<Integer> lines = new ArrayList<Integer>();
    List<int[]> lineTokens = new ArrayList<int[]>();
    for (int[] range : requests) {
      postRange(range[0], range[1], lines, lineTokens, cancel);
    }
    requests.clear();
    int viewEnd = -1;
    for (int[] range : viewports.values()) {
      viewEnd = Math.max(viewEnd, range[1]);
    }
    boolean reached = frontier > viewEnd;
    int from = -1;
    int to = -1;
    int count = 0;
    while (frontier < lineCount) {
      int line = frontier;
      int end = tokenizeLine(line, states[line], cancel);
      boolean inSpan = tokenizer.inSpan(states[line]);
      folds[line] = foldCounts(inSpan, cancel);
      if (marks != null) {
        marks[line] = inSpan || lineLength > tokenizer.maxLineChars() ? 0 : regionCounts(cancel);
      }
      if (from < 0) {
        from = line;
      }
      to = line + 1;
      if (inViewport(line)) {
        lines.add(line);
        lineTokens.add(tokens.toArray());
      }
      int next = line + 1;
      if (next < lineCount && next <= known && next > dirtyEnd && states[next] == end) {
        frontier = Math.max(known, next);
      } else {
        states[next] = end;
        frontier = next;
        known = Math.max(known, frontier);
      }
      count++;
      if (count >= maxLines
          || System.nanoTime() - deadline > 0
          || (!reached && frontier > viewEnd)) {
        break;
      }
    }
    if (frontier >= lineCount) {
      dirtyEnd = -1;
    }
    List<int[]> regions = null;
    int overLong = 0;
    if (frontier >= lineCount && !regionsDone) {
      regionsDone = true;
      regions = regions();
      for (int i = 0; i < lineCount; i++) {
        if ((folds[i] & OVERLONG) != 0) {
          overLong++;
        }
      }
    }
    int[] lineArray = new int[lines.size()];
    for (int i = 0; i < lineArray.length; i++) {
      lineArray[i] = lines.get(i);
    }
    return new Slice(
        lineArray, lineTokens.toArray(new int[0][]), from, to, regions, overLong, idle());
  }

  /** Tokenizes view lines now, from the best start state stored. */
  @WorkerThread
  private void postRange(
      int first, int last, List<Integer> lines, List<int[]> lineTokens, CancellationToken cancel) {
    int from = Math.max(0, first);
    int to = Math.min(last, lineCount - 1);
    if (from > to) {
      return;
    }
    int state = from <= Math.max(frontier, known) ? states[from] : Tokenizer.INITIAL;
    for (int line = from; line <= to; line++) {
      int end = tokenizeLine(line, state, cancel);
      lines.add(line);
      lineTokens.add(tokens.toArray());
      state = end;
    }
  }

  private boolean inViewport(int line) {
    for (int[] range : viewports.values()) {
      if (line >= range[0] && line <= range[1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tokenizes a line into {@link #tokens}, keeping a copy of its chars unless it is over the limit,
   * and returns its end state.
   */
  @WorkerThread
  private int tokenizeLine(int line, int state, CancellationToken cancel) {
    if (line >= blockFirst && line < blockFirst + blockLines) {
      int at = (line - blockFirst) * 2;
      lineText = block.substring(blockBounds[at], blockBounds[at + 1]);
      lineLength = lineText.length();
      return tokenizer.tokenize(lineText, state, tokens, cancel);
    }
    LineView v = text.lineView(line, view);
    lineLength = v.length();
    if (lineLength > tokenizer.maxLineChars()) {
      lineText = "";
      return tokenizer.tokenize(v, state, tokens, cancel);
    }
    if (loadBlock(line)) {
      return tokenizeLine(line, state, cancel);
    }
    lineText = v.toString();
    return tokenizer.tokenize(lineText, state, tokens, cancel);
  }

  /**
   * Copies up to {@value #BLOCK_LINES} lines from {@code first} in one read when they hold at most
   * {@value #BLOCK_CHARS} chars; a line view locates its line from its leaf's start, so line by
   * line reads cost a leaf scan each.
   */
  private boolean loadBlock(int first) {
    int last = Math.min(lineCount, first + BLOCK_LINES);
    int start = text.offsetOfLine(first);
    int end = last < lineCount ? text.offsetOfLine(last) : text.length();
    if (end - start > BLOCK_CHARS) {
      return false;
    }
    block = text.subSequence(start, end).toString();
    blockFirst = first;
    blockLines = last - first;
    int from = 0;
    for (int k = 0; k < blockLines; k++) {
      int stop = from;
      while (stop < block.length() && block.charAt(stop) != '\n' && block.charAt(stop) != '\r') {
        stop++;
      }
      blockBounds[k * 2] = from;
      blockBounds[k * 2 + 1] = stop;
      boolean crLf =
          stop + 1 < block.length() && block.charAt(stop) == '\r' && block.charAt(stop + 1) == '\n';
      from = stop < block.length() ? stop + (crLf ? 2 : 1) : stop;
    }
    return true;
  }

  /**
   * Counts the opening and closing fold marks outside comments and strings of the line {@link
   * #tokens} holds, packed; an over-long line gets the over-long flag only. In {@link
   * Grammar.FoldMode#SECTIONS} mode returns the header level, the previous-line flag and the blank
   * flag instead.
   */
  private int foldCounts(boolean inSpan, CancellationToken cancel) {
    if (lineLength > tokenizer.maxLineChars()) {
      return OVERLONG;
    }
    String v = lineText;
    int opens = 0;
    int closes = 0;
    switch (folding.mode()) {
      case BRACKETS -> {
        String open = folding.open();
        String close = folding.close();
        for (int i = 0; i < v.length(); i++) {
          char c = v.charAt(i);
          if (c < brackets.length && !brackets[c]) {
            continue;
          }
          boolean isOpen = open.indexOf(c) >= 0;
          if ((isOpen || close.indexOf(c) >= 0) && code(tokens.typeAt(i))) {
            if (isOpen) {
              opens++;
            } else if (opens > 0) {
              opens--;
            } else {
              closes++;
            }
          }
        }
      }
      case MARKERS -> {
        int[] events = markers(v, cancel);
        for (int i = 0; i < events.length; i += 2) {
          if (events[i + 1] > 0) {
            opens++;
          } else if (opens > 0) {
            opens--;
          } else {
            closes++;
          }
        }
      }
      case SECTIONS -> {
        return v.isBlank() ? BLANK : inSpan ? 0 : header(v, cancel);
      }
      default -> {
        return 0;
      }
    }
    return Math.min(opens, OPEN_MASK) | (Math.min(closes, OPEN_MASK) << CLOSE_SHIFT);
  }

  /** Returns the first section rule matching at the line's start as level and flag, or 0. */
  private int header(String v, CancellationToken cancel) {
    try {
      for (int i = 0; i < sections.length; i++) {
        Matcher m = matchAtStart(sections[i], v, cancel);
        if (m != null) {
          Grammar.Section rule = folding.sections().get(i);
          int level = rule.level();
          if (m.pattern().namedGroups().containsKey("level") && m.start("level") >= 0) {
            level = Math.max(1, Math.min(LEVEL_MASK, m.end("level") - m.start("level")));
          }
          return level | (rule.previous() ? PREVIOUS : 0);
        }
      }
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      return 0;
    }
    return 0;
  }

  /** Returns the region markers of the line {@link #tokens} holds as packed counts. */
  private int regionCounts(CancellationToken cancel) {
    try {
      if (matchAtStart(regionBegin, lineText, cancel) != null) {
        return 1;
      }
      if (matchAtStart(regionEnd, lineText, cancel) != null) {
        return 1 << CLOSE_SHIFT;
      }
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      return 0;
    }
    return 0;
  }

  /**
   * Matches a regex at a line's start; returns the matcher when it matches and the token at the
   * match's first non-blank char is not a string or an escape, or a comment unless the matcher is a
   * region marker.
   */
  private Matcher matchAtStart(Matcher m, String v, CancellationToken cancel) {
    markerChars.reset(v, BoundedChars.lineLimit(v), cancel);
    m.reset();
    if (!m.lookingAt()) {
      return null;
    }
    int at = m.start();
    while (at < m.end() && Character.isWhitespace(v.charAt(at))) {
      at++;
    }
    if (at >= v.length()) {
      return m;
    }
    TokenType type = tokens.typeAt(at);
    boolean comment = type == TokenType.COMMENT && (m == regionBegin || m == regionEnd);
    return code(type) || comment ? m : null;
  }

  /** Returns marker matches as {@code position, +1 or -1} pairs by position. */
  private int[] markers(String v, CancellationToken cancel) {
    List<int[]> events = new ArrayList<int[]>();
    try {
      markerChars.reset(v, BoundedChars.lineLimit(v), cancel);
      collect(markerBegin, v.length(), 1, events);
      markerChars.reset(v, BoundedChars.lineLimit(v), cancel);
      collect(markerEnd, v.length(), -1, events);
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      return new int[0];
    }
    events.sort(
        new Comparator<int[]>() {
          @Override
          public int compare(int[] a, int[] b) {
            return Integer.compare(a[0], b[0]);
          }
        });
    int[] out = new int[events.size() * 2];
    for (int i = 0; i < events.size(); i++) {
      out[i * 2] = events.get(i)[0];
      out[i * 2 + 1] = events.get(i)[1];
    }
    return out;
  }

  private void collect(Matcher m, int length, int sign, List<int[]> events) {
    m.reset();
    int at = 0;
    while (at <= length && m.find(at)) {
      if (code(tokens.typeAt(m.start()))) {
        events.add(new int[] {m.start(), sign});
      }
      at = Math.max(m.end(), m.start() + 1);
    }
  }

  private static boolean code(TokenType type) {
    return type != TokenType.COMMENT && type != TokenType.STRING && type != TokenType.ESCAPE;
  }

  /** Computes the regions of every source and merges them. */
  private List<int[]> regions() {
    List<int[]> main =
        switch (folding.mode()) {
          case BRACKETS, MARKERS -> pairs(folds);
          case SECTIONS -> sections();
          default -> List.of();
        };
    List<int[]> out = FoldRegions.merge(main, spans());
    return marks == null ? out : FoldRegions.merge(out, pairs(marks));
  }

  /**
   * Pairs packed open and close counts into regions: one per start line, the largest, spanning at
   * least two lines.
   */
  private List<int[]> pairs(int[] counts) {
    int[] open = new int[64];
    int depth = 0;
    int[] endOf = new int[lineCount];
    Arrays.fill(endOf, -1);
    for (int line = 0; line < lineCount; line++) {
      int f = counts[line];
      if ((f & OVERLONG) != 0) {
        continue;
      }
      int closes = (f >>> CLOSE_SHIFT) & OPEN_MASK;
      for (int j = 0; j < closes && depth > 0; j++) {
        int start = open[--depth];
        if (line > start && line > endOf[start]) {
          endOf[start] = line;
        }
      }
      int opens = f & OPEN_MASK;
      for (int j = 0; j < opens; j++) {
        if (depth == open.length) {
          open = Arrays.copyOf(open, depth * 2);
        }
        open[depth++] = line;
      }
    }
    return ascending(endOf);
  }

  /**
   * Returns the folding spans covering two or more lines: from the line ending inside a span to the
   * line ending outside it. A span left open runs to the last line.
   */
  private List<int[]> spans() {
    List<int[]> out = new ArrayList<int[]>();
    int openLine = -1;
    int openKey = 0;
    for (int line = 0; line < lineCount; line++) {
      int key = tokenizer.foldSpan(states[line + 1]);
      if (openLine >= 0 && key != openKey) {
        out.add(new int[] {openLine, line});
        openLine = -1;
      }
      if (key != 0 && openLine < 0) {
        openLine = line;
        openKey = key;
      }
    }
    if (openLine >= 0 && openLine < lineCount - 1) {
      out.add(new int[] {openLine, lineCount - 1});
    }
    return out;
  }

  /**
   * Returns the sections: each header runs to the line before the next header of the same or a
   * higher level, or to the last non-blank line. A previous-line rule makes the line above it the
   * header when that line is not blank, not a header and not inside a span.
   */
  private List<int[]> sections() {
    byte[] level = new byte[lineCount];
    int last = -1;
    for (int line = 0; line < lineCount; line++) {
      int f = folds[line];
      if ((f & OVERLONG) != 0 || (f & BLANK) == 0) {
        last = line;
      }
      if ((f & OVERLONG) != 0 || (f & LEVEL_MASK) == 0) {
        continue;
      }
      int header = line;
      if ((f & PREVIOUS) != 0) {
        header = line - 1;
        if (header < 0
            || (folds[header] & (BLANK | LEVEL_MASK | OVERLONG)) != 0
            || tokenizer.inSpan(states[header])) {
          continue;
        }
      }
      level[header] = (byte) (f & LEVEL_MASK);
    }
    int[] endOf = new int[lineCount];
    Arrays.fill(endOf, -1);
    int[] open = new int[LEVEL_MASK + 1];
    int depth = 0;
    for (int line = 0; line < lineCount; line++) {
      if (level[line] == 0) {
        continue;
      }
      while (depth > 0 && level[open[depth - 1]] >= level[line]) {
        int start = open[--depth];
        endOf[start] = line - 1;
      }
      if (depth == open.length) {
        open = Arrays.copyOf(open, depth * 2);
      }
      open[depth++] = line;
    }
    while (depth > 0) {
      int start = open[--depth];
      endOf[start] = last;
    }
    return ascending(endOf);
  }

  /** Returns {@code {line, endOf[line]}} for each line whose end lies below it, ascending. */
  private static List<int[]> ascending(int[] endOf) {
    List<int[]> out = new ArrayList<int[]>();
    for (int line = 0; line < endOf.length; line++) {
      if (endOf[line] > line) {
        out.add(new int[] {line, endOf[line]});
      }
    }
    return out;
  }

  private void ensure(int capacity) {
    if (states.length < capacity) {
      int size = Math.max(capacity, states.length + (states.length >> 1));
      states = Arrays.copyOf(states, size);
      folds = Arrays.copyOf(folds, size);
      if (marks != null) {
        marks = Arrays.copyOf(marks, size);
      }
    }
  }
}
