package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Tokenizes one line at a time from a start state to an end state. A state is a context stack,
 * interned to an int: equal stacks have equal ids, and {@link #INITIAL} is the grammar's main
 * context. Not thread-safe; one tokenizer per thread.
 *
 * <ul>
 *   <li>A line longer than the limit gets no tokens and ends in its start state.
 *   <li>Each regex search reads the line through a {@link BoundedChars} that ends it after a read
 *       count proportional to the line and the context's rule count, and checks the cancellation
 *       token. A search that reaches the count or overflows the stack leaves the rest of the line
 *       plain; a cancel throws {@link java.util.concurrent.CancellationException}.
 *   <li>At a line's end, spans that do not continue past it are closed.
 *   <li>A push that would make the stack deeper than {@value #MAX_DEPTH} is skipped, so a file
 *       opening nested comments without end does not grow the states without bound.
 * </ul>
 */
public final class Tokenizer {
  /** The state of a file's first line. */
  public static final int INITIAL = 0;

  /** Context changes allowed at one position without consuming a char. */
  private static final int ZERO_WIDTH_STEPS = 16;

  /** Deepest context stack; a push beyond it is skipped. */
  static final int MAX_DEPTH = 256;

  private final Syntax syntax;
  private final int maxLineChars;
  private final BoundedChars chars = new BoundedChars();
  private final Map<Syntax.Combined, Matcher> matchers =
      new IdentityHashMap<Syntax.Combined, Matcher>();
  private final Map<Stack, Integer> ids = new HashMap<Stack, Integer>();
  private final List<Stack> stacks = new ArrayList<Stack>();
  private int[] work = new int[8];
  private Syntax.Combined[] plain = new Syntax.Combined[16];
  private int depth;
  private boolean failed;

  /** An interned context stack. */
  private record Stack(int[] contexts) {
    @Override
    public boolean equals(Object other) {
      return other instanceof Stack s && Arrays.equals(contexts, s.contexts);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(contexts);
    }
  }

  Tokenizer(Syntax syntax, int maxLineChars) {
    this.syntax = syntax;
    this.maxLineChars = maxLineChars;
    work[0] = 0;
    depth = 1;
    intern();
  }

  /**
   * Returns the line length above which a line gets no tokens.
   *
   * @return chars
   */
  public int maxLineChars() {
    return maxLineChars;
  }

  /**
   * Reports whether the last {@link #tokenize} stopped early on its read count or a stack overflow.
   *
   * @return true when part of the line was left plain
   */
  public boolean failed() {
    return failed;
  }

  /**
   * Tokenizes a line.
   *
   * @param line the line without its break
   * @param state the start state, {@link #INITIAL} or one this tokenizer returned
   * @param out receives the tokens, cleared first
   * @param token checked while reading
   * @return the end state
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public int tokenize(CharSequence line, int state, TokenList out, CancellationToken token) {
    out.clear();
    failed = false;
    if (line.length() > maxLineChars) {
      return state;
    }
    load(state);
    int length = line.length();
    chars.reset(line, Long.MAX_VALUE, token);
    int pos = 0;
    int zeroWidth = 0;
    try {
      while (pos <= length) {
        Syntax.Context context = syntax.context(work[depth - 1]);
        Syntax.Combined combined = combined(context.id, embedFrame());
        Matcher m = null;
        Syntax.Combined used = null;
        int start = pos;
        for (int at = pos; at <= length && m == null; at++) {
          Syntax.Combined subset =
              at < length
                  ? combined.forChar(line.charAt(at), at > 0 && word(line.charAt(at - 1)))
                  : combined.atEnd();
          if (subset != null) {
            Matcher candidate = matcher(subset);
            chars.allow(
                4L * (subset.alts().length + 2) * (length - at + 1) + BoundedChars.BASE_READS);
            candidate.region(at, length);
            if (candidate.lookingAt()) {
              m = candidate;
              used = subset;
              // Matcher.start() can report an earlier offset after a lookbehind at the region
              // start (JDK 21); a lookingAt match starts at the region start.
              start = at;
            }
          }
        }
        if (m == null) {
          out.add(pos, length, context.content);
          break;
        }
        int end = m.end();
        out.add(pos, start, context.content);
        int index = matched(m, used);
        Syntax.Alt alt = used.alts()[index];
        int before = depth;
        int top = work[depth - 1];
        int target = alt.target();
        if (alt.kind() == Syntax.Kind.SPAN_BEGIN && syntax.context(target).template != null) {
          int group = used.groups()[index] + 1;
          String captured =
              m.start(group) < 0 ? "" : line.subSequence(m.start(group), m.end(group)).toString();
          target = syntax.derive(target, captured);
        }
        apply(alt, target, out, start, end);
        if (alt.kind() == Syntax.Kind.EMBED_END) {
          end = start;
        }
        if (end > start) {
          pos = end;
          zeroWidth = 0;
        } else if (depth != before || work[depth - 1] != top) {
          pos = start;
          if (++zeroWidth > ZERO_WIDTH_STEPS) {
            out.add(start, Math.min(start + 1, length), syntax.context(work[depth - 1]).content);
            pos = start + 1;
            zeroWidth = 0;
          }
        } else {
          out.add(start, Math.min(start + 1, length), context.content);
          pos = start + 1;
        }
      }
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      failed = true;
    }
    while (depth > 1 && syntax.context(work[depth - 1]).lineEnd) {
      depth--;
    }
    return intern();
  }

  /** Applies a matched rule; {@code target} is its context, or a derived one, or -1. */
  private void apply(Syntax.Alt alt, int target, TokenList out, int start, int end) {
    switch (alt.kind()) {
      case MATCH -> {
        out.add(start, end, alt.token());
        if (alt.pop() && depth > 1) {
          depth--;
        }
        if (target >= 0) {
          push(target);
        }
      }
      case SPAN_BEGIN, EMBED_BEGIN -> {
        out.add(start, end, alt.token());
        if (target >= 0) {
          push(target);
        }
      }
      case SPAN_END -> {
        out.add(start, end, alt.token());
        if (depth > 1) {
          depth--;
        }
      }
      case ESCAPE -> out.add(start, end, TokenType.ESCAPE);
      case EMBED_END -> {
        int frame = embedFrame();
        while (depth > 1 && work[depth - 1] != frame) {
          depth--;
        }
        if (depth > 1) {
          depth--;
        }
      }
    }
  }

  private static boolean word(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  /** Returns the index of the alternative that matched. */
  private static int matched(Matcher m, Syntax.Combined combined) {
    int[] groups = combined.groups();
    for (int i = 0; i < groups.length; i++) {
      if (m.start(groups[i]) >= 0) {
        return i;
      }
    }
    throw new IllegalStateException("no alternative matched");
  }

  /** Returns a context's alternatives, cached per tokenizer outside an embed. */
  private Syntax.Combined combined(int context, int embed) {
    if (embed >= 0) {
      return syntax.combined(context, embed);
    }
    if (context >= plain.length) {
      plain = Arrays.copyOf(plain, Math.max(context + 1, plain.length * 2));
    }
    Syntax.Combined found = plain[context];
    if (found == null) {
      found = syntax.combined(context, -1);
      plain[context] = found;
    }
    return found;
  }

  private Matcher matcher(Syntax.Combined combined) {
    Matcher m = matchers.get(combined);
    if (m == null) {
      m = combined.pattern().matcher(chars);
      m.useTransparentBounds(true);
      m.useAnchoringBounds(false);
      matchers.put(combined, m);
    }
    return m;
  }

  /** Returns the context id of the innermost embed frame on the work stack, or -1. */
  private int embedFrame() {
    for (int i = depth - 1; i > 0; i--) {
      if (syntax.context(work[i]).embedEnd != null) {
        return work[i];
      }
    }
    return -1;
  }

  private void push(int context) {
    if (depth >= MAX_DEPTH) {
      return;
    }
    if (depth == work.length) {
      work = Arrays.copyOf(work, depth * 2);
    }
    work[depth++] = context;
  }

  private void load(int state) {
    int[] contexts = stacks.get(state).contexts();
    if (work.length < contexts.length) {
      work = Arrays.copyOf(contexts, contexts.length * 2);
    } else {
      System.arraycopy(contexts, 0, work, 0, contexts.length);
    }
    depth = contexts.length;
  }

  private int intern() {
    Stack key = new Stack(Arrays.copyOf(work, depth));
    Integer id = ids.get(key);
    if (id == null) {
      id = stacks.size();
      ids.put(key, id);
      stacks.add(key);
    }
    return id;
  }

  /**
   * Returns the depth of a state's context stack.
   *
   * @param state a state this tokenizer returned
   * @return 1 for the main context
   */
  public int depth(int state) {
    return stacks.get(state).contexts().length;
  }
}
