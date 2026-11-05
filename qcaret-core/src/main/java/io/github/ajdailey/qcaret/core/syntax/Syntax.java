package io.github.ajdailey.qcaret.core.syntax;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A grammar compiled for tokenizing, with the grammars it embeds. Each context becomes one regex
 * that alternates its rules, so a match at a position is the first rule matching there. Includes
 * are expanded in place; a span becomes a context of its own holding its escape, its end and its
 * inner rules. Inside an embedded grammar, the embed's end is tried before every rule of the
 * current context. Thread-safe; {@link Tokenizer} holds the per-thread state.
 *
 * <p>Each rule's possible first chars are found by matching its regex, without a leading lookbehind
 * or word boundary, against each ASCII char: a char that matches or needs more input may start it.
 * A rule whose leading negative lookbehind matches every ASCII letter, digit and underscore cannot
 * start after one. Each context keeps one regex per distinct set of rules a position may start, so
 * the tokenizer tries at each position only those rules, and none inside a word that only such
 * rules could start; a non-ASCII char tries every rule.
 *
 * <p>A span whose end or escape uses {@code \1} compiles to a template context without them. The
 * tokenizer takes from {@link #derive} one context per text the begin's first group matched: the
 * template's rules with {@code \1} replaced by that text, quoted. A syntax derives at most {@value
 * #MAX_DERIVED} contexts, each for a text of at most {@value #MAX_CAPTURE} chars.
 *
 * <p>A {@code bodyNextLine} span's begin pushes a pending context from {@link #pending}, which the
 * tokenizer replaces with the span's body at the line's end.
 */
public final class Syntax {
  /** Rejected regex features: backreferences and named groups, which alternation renumbers. */
  private static final Pattern UNSUPPORTED =
      Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\\\(?:[1-9]|k<)|\\(\\?<[A-Za-z]");

  /** A {@code \1} not escaped and not followed by a digit; group 1 holds escaped backslashes. */
  private static final Pattern GROUP_ONE =
      Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\1(?![0-9])");

  /** Contexts {@link #derive} creates per syntax at most. */
  static final int MAX_DERIVED = 512;

  /** Longest captured text {@link #derive} accepts. */
  static final int MAX_CAPTURE = 64;

  /** What a matched rule does. */
  enum Kind {
    MATCH,
    SPAN_BEGIN,
    SPAN_END,
    ESCAPE,
    EMBED_BEGIN,
    EMBED_END
  }

  /** Index of the non-ASCII entry in a first-char table. */
  static final int NON_ASCII = 128;

  /**
   * One alternative of a context.
   *
   * @param starts per ASCII char, and {@link #NON_ASCII}, whether a match may start with it
   * @param atEnd whether an empty match may stand at a line's end
   * @param afterWord whether a match may start right after an ASCII letter, digit or underscore
   */
  record Alt(
      Pattern pattern,
      Kind kind,
      TokenType token,
      int target,
      boolean pop,
      boolean[] starts,
      boolean atEnd,
      boolean afterWord) {
    static Alt of(Pattern pattern, Kind kind, TokenType token, int target, boolean pop) {
      Pattern probe = probe(pattern.pattern());
      boolean[] starts = new boolean[NON_ASCII + 1];
      starts[NON_ASCII] = true;
      for (int c = 0; c < NON_ASCII; c++) {
        Matcher m = probe == null ? null : probe.matcher(String.valueOf((char) c));
        starts[c] = m == null || m.lookingAt() || m.hitEnd();
      }
      boolean atEnd = probe == null || probe.matcher("").lookingAt();
      return new Alt(
          pattern, kind, token, target, pop, starts, atEnd, Syntax.afterWord(pattern.pattern()));
    }
  }

  /** One compiled context. */
  static final class Context {
    final int id;
    final String name;
    final TokenType content;
    final boolean lineEnd;
    final Alt embedEnd;
    final List<Alt> alts = new ArrayList<Alt>();

    /** For a template context, the span whose end or escape uses {@code \1}; else null. */
    final Grammar.Span template;

    /** True for a span body that starts at the line after its begin. */
    boolean nextLine;

    /**
     * For a pending context, the body it becomes at the line's end; else -1. A pending context has
     * no rules: the tokenizer applies the context below it.
     */
    int body = -1;

    Context(
        int id,
        String name,
        TokenType content,
        boolean lineEnd,
        Alt embedEnd,
        Grammar.Span template) {
      this.id = id;
      this.name = name;
      this.content = content;
      this.lineEnd = lineEnd;
      this.embedEnd = embedEnd;
      this.template = template;
    }
  }

  /**
   * Alternatives as one regex, with the first group of each alternative.
   *
   * @param byChar per ASCII char, and {@link #NON_ASCII}, the alternatives a match starting with it
   *     may use, or null for none; null in such a subset itself
   * @param atEnd the alternatives an empty match at a line's end may use, or null
   */
  record Combined(
      Pattern pattern,
      Alt[] alts,
      int[] groups,
      Combined[] byChar,
      Combined[] afterWord,
      Combined atEnd) {
    /**
     * Returns the alternatives a match starting with a char may use.
     *
     * @param c the char
     * @param inWord true when the char before it is an ASCII letter, digit or underscore
     * @return the subset, or null for none
     */
    Combined forChar(char c, boolean inWord) {
      return (inWord ? afterWord : byChar)[c < NON_ASCII ? c : NON_ASCII];
    }
  }

  private final Grammar grammar;
  private final List<Context> contexts;
  private final Map<Long, Combined> combined = new ConcurrentHashMap<Long, Combined>();
  private final Map<String, Integer> derived = new ConcurrentHashMap<String, Integer>();
  private final Map<Integer, Integer> pending = new ConcurrentHashMap<Integer, Integer>();

  private Syntax(Grammar grammar, List<Context> contexts) {
    this.grammar = grammar;
    this.contexts = contexts;
  }

  /**
   * Compiles a grammar and the grammars it embeds; an embed of a grammar {@code lookup} does not
   * know is skipped.
   *
   * @param grammar the grammar
   * @param lookup returns a grammar by id, or null
   * @return the compiled syntax
   * @throws InvalidGrammarException when a regex uses a backreference or a named group, or the
   *     alternation of a context does not compile
   */
  public static Syntax compile(Grammar grammar, Function<String, Grammar> lookup)
      throws InvalidGrammarException {
    Compiler compiler = new Compiler(lookup);
    try {
      compiler.context(grammar, Grammar.MAIN);
    } catch (IllegalArgumentException e) {
      throw new InvalidGrammarException(grammar.id() + ": " + e.getMessage(), e);
    }
    Syntax syntax = new Syntax(grammar, new CopyOnWriteArrayList<Context>(compiler.contexts));
    for (Context c : compiler.contexts) {
      try {
        syntax.combined(c.id, -1);
      } catch (IllegalArgumentException e) {
        throw new InvalidGrammarException(grammar.id() + ": context " + c.name + ": " + e, e);
      }
    }
    return syntax;
  }

  /**
   * Returns the grammar.
   *
   * @return the grammar compiled
   */
  public Grammar grammar() {
    return grammar;
  }

  /**
   * Creates a tokenizer, for one thread.
   *
   * @param maxLineChars lines longer than this get no tokens
   * @return the tokenizer
   */
  public Tokenizer tokenizer(int maxLineChars) {
    return new Tokenizer(this, maxLineChars);
  }

  Context context(int id) {
    return contexts.get(id);
  }

  /**
   * Returns the context of a template span for the text its begin's first group matched, created on
   * first use.
   *
   * @param template a template context
   * @param text the captured text
   * @return the context's id, or -1 when the text is too long or holds a backslash, the derived
   *     regexes do not compile, or the syntax holds {@value #MAX_DERIVED} derived contexts
   */
  int derive(int template, String text) {
    if (text.length() > MAX_CAPTURE || text.indexOf('\\') >= 0) {
      return -1;
    }
    String key = template + ":" + text;
    Integer found = derived.get(key);
    if (found != null) {
      return found;
    }
    synchronized (this) {
      found = derived.get(key);
      if (found != null) {
        return found;
      }
      if (derived.size() >= MAX_DERIVED) {
        return -1;
      }
      Context t = contexts.get(template);
      Grammar.Span s = t.template;
      Context c =
          new Context(contexts.size(), t.name + ":" + text, t.content, t.lineEnd, null, null);
      c.nextLine = t.nextLine;
      try {
        if (s.escape() != null) {
          c.alts.add(
              Alt.of(substitute(s.escape(), text), Kind.ESCAPE, TokenType.ESCAPE, -1, false));
        }
        if (s.end() != null) {
          c.alts.add(Alt.of(substitute(s.end(), text), Kind.SPAN_END, s.token(), -1, true));
        }
        c.alts.addAll(t.alts.subList(fixed(s), t.alts.size()));
        alternation(c.alts);
      } catch (IllegalArgumentException e) {
        return -1;
      }
      contexts.add(c);
      derived.put(key, c.id);
      return c.id;
    }
  }

  /**
   * Returns the pending context of a span body, created on first use.
   *
   * @param body a context whose {@code nextLine} is set
   * @return the pending context's id
   */
  int pending(int body) {
    Integer found = pending.get(body);
    if (found != null) {
      return found;
    }
    synchronized (this) {
      found = pending.get(body);
      if (found == null) {
        Context b = contexts.get(body);
        Context c = new Context(contexts.size(), b.name + ":pending", null, false, null, null);
        c.body = body;
        contexts.add(c);
        found = c.id;
        pending.put(body, found);
      }
      return found;
    }
  }

  /**
   * Reports whether a span's end or escape uses {@code \1} for the text its begin captured.
   *
   * @param regex the end or escape, or null
   * @return true when it does
   */
  static boolean templated(Pattern regex) {
    return regex != null && GROUP_ONE.matcher(regex.pattern()).find();
  }

  /** Returns a regex with each {@code \1} replaced by a text, quoted. */
  private static Pattern substitute(Pattern regex, String text) {
    return Pattern.compile(
        GROUP_ONE
            .matcher(regex.pattern())
            .replaceAll("$1" + Matcher.quoteReplacement(Pattern.quote(text))));
  }

  /** Returns how many of a template span's escape and end its template context holds. */
  private static int fixed(Grammar.Span s) {
    int n = 0;
    if (s.escape() != null && !templated(s.escape())) {
      n++;
    }
    if (s.end() != null && !templated(s.end())) {
      n++;
    }
    return n;
  }

  /** Returns a context's regex, with the end of the embed frame {@code embed} first when given. */
  Combined combined(int context, int embed) {
    long key = ((long) context << 32) | (embed & 0xffffffffL);
    Combined found = combined.get(key);
    if (found == null) {
      found = build(contexts.get(context), embed < 0 ? null : contexts.get(embed).embedEnd);
      Combined raced = combined.putIfAbsent(key, found);
      if (raced != null) {
        found = raced;
      }
    }
    return found;
  }

  private static Combined build(Context context, Alt end) {
    List<Alt> alts = new ArrayList<Alt>();
    if (end != null) {
      alts.add(end);
    }
    alts.addAll(context.alts);
    Map<String, Combined> subsets = new HashMap<String, Combined>();
    Combined[] byChar = new Combined[NON_ASCII + 1];
    Combined[] afterWord = new Combined[NON_ASCII + 1];
    for (int c = 0; c <= NON_ASCII; c++) {
      List<Alt> subset = new ArrayList<Alt>();
      List<Alt> inWord = new ArrayList<Alt>();
      for (Alt alt : alts) {
        if (alt.starts()[c]) {
          subset.add(alt);
          if (alt.afterWord()) {
            inWord.add(alt);
          }
        }
      }
      byChar[c] = subset(subset, subsets);
      afterWord[c] = subset(inWord, subsets);
    }
    List<Alt> ending = new ArrayList<Alt>();
    for (Alt alt : alts) {
      if (alt.atEnd()) {
        ending.add(alt);
      }
    }
    Combined all = alternation(alts);
    return new Combined(
        all.pattern(), all.alts(), all.groups(), byChar, afterWord, subset(ending, subsets));
  }

  /** Returns the alternation of a subset, one instance per distinct subset; null when empty. */
  private static Combined subset(List<Alt> alts, Map<String, Combined> built) {
    if (alts.isEmpty()) {
      return null;
    }
    StringBuilder key = new StringBuilder();
    for (Alt alt : alts) {
      key.append(System.identityHashCode(alt)).append(',');
    }
    Combined found = built.get(key.toString());
    if (found == null) {
      found = alternation(alts);
      built.put(key.toString(), found);
    }
    return found;
  }

  private static Combined alternation(List<Alt> alts) {
    StringBuilder regex = new StringBuilder();
    int[] groups = new int[alts.size()];
    int group = 1;
    for (int i = 0; i < alts.size(); i++) {
      Pattern p = alts.get(i).pattern();
      String source = p.pattern();
      if (UNSUPPORTED.matcher(source).find()) {
        throw new IllegalArgumentException("backreference or named group in " + source);
      }
      if (i > 0) {
        regex.append('|');
      }
      regex.append('(').append(source).append(')');
      groups[i] = group;
      group += 1 + p.matcher("").groupCount();
    }
    // A context with no rules never matches.
    Pattern pattern = alts.isEmpty() ? Pattern.compile("(?!)") : Pattern.compile(regex.toString());
    return new Combined(pattern, alts.toArray(new Alt[0]), groups, null, null, null);
  }

  /**
   * Reports whether a regex may match right after an ASCII letter, digit or underscore: false when
   * one of its leading lookarounds is a negative lookbehind matching each of them.
   */
  static boolean afterWord(String source) {
    int at = 0;
    Matcher flags = Pattern.compile("^\\(\\?[a-zA-Z]+\\)").matcher(source);
    if (flags.find()) {
      at = flags.end();
    }
    while (source.startsWith("(?=", at)
        || source.startsWith("(?!", at)
        || source.startsWith("(?<=", at)
        || source.startsWith("(?<!", at)) {
      int close = closing(source, at);
      if (close < 0) {
        return true;
      }
      if (source.startsWith("(?<!", at) && excludesWordChars(source.substring(at + 4, close))) {
        return false;
      }
      at = close + 1;
    }
    return true;
  }

  private static boolean excludesWordChars(String body) {
    Pattern p;
    try {
      p = Pattern.compile(body);
    } catch (IllegalArgumentException e) {
      return false;
    }
    for (char c = 0; c < NON_ASCII; c++) {
      boolean word = Character.isLetterOrDigit(c) || c == '_';
      if (word && !p.matcher(String.valueOf(c)).matches()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a regex's source without a leading inline flag group's lookbehinds and word boundaries,
   * compiled for probing first chars; null when it cannot be read that way.
   */
  static Pattern probe(String source) {
    int at = 0;
    String flags = "";
    Matcher m = Pattern.compile("^\\(\\?[a-zA-Z]+\\)").matcher(source);
    if (m.find()) {
      flags = m.group();
      at = m.end();
    }
    while (true) {
      if (source.startsWith("(?<=", at) || source.startsWith("(?<!", at)) {
        int close = closing(source, at);
        if (close < 0) {
          return null;
        }
        at = close + 1;
      } else if (source.startsWith("\\b", at) || source.startsWith("\\B", at)) {
        at += 2;
      } else {
        break;
      }
    }
    try {
      return Pattern.compile(flags + source.substring(at));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Returns the index of the parenthesis closing the group opened at {@code open}, or -1. */
  private static int closing(String source, int open) {
    int depth = 0;
    boolean inClass = false;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        if (source.startsWith("\\Q", i)) {
          return -1;
        }
        i++;
      } else if (inClass) {
        inClass = c != ']';
      } else if (c == '[') {
        inClass = true;
        if (i + 1 < source.length() && source.charAt(i + 1) == ']') {
          i++;
        }
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /** Builds the contexts; not thread-safe. */
  private static final class Compiler {
    final Function<String, Grammar> lookup;
    final List<Context> contexts = new ArrayList<Context>();
    final Map<String, Integer> named = new HashMap<String, Integer>();

    Compiler(Function<String, Grammar> lookup) {
      this.lookup = lookup;
    }

    /** Returns the id of a named context of a grammar, compiling it first. */
    int context(Grammar g, String name) {
      String key = g.id() + ":" + name;
      Integer id = named.get(key);
      if (id != null) {
        return id;
      }
      Context c = add(key, null, false, null, null);
      named.put(key, c.id);
      expand(g, name, c.alts, new HashSet<String>());
      return c.id;
    }

    Context add(
        String name, TokenType content, boolean lineEnd, Alt embedEnd, Grammar.Span template) {
      Context c = new Context(contexts.size(), name, content, lineEnd, embedEnd, template);
      contexts.add(c);
      return c;
    }

    /** Appends a context's rules as alternatives, expanding includes. */
    void expand(Grammar g, String name, List<Alt> out, Set<String> visiting) {
      if (!visiting.add(name)) {
        return;
      }
      for (Grammar.Rule rule : g.contexts().get(name)) {
        if (rule instanceof Grammar.Match m) {
          int target = m.push() == null ? -1 : context(g, m.push());
          out.add(Alt.of(m.regex(), Kind.MATCH, m.token(), target, m.pop()));
        } else if (rule instanceof Grammar.Keywords k) {
          out.add(Alt.of(keywords(k), Kind.MATCH, k.token(), -1, false));
        } else if (rule instanceof Grammar.Span s) {
          out.add(Alt.of(s.begin(), Kind.SPAN_BEGIN, s.token(), span(g, s), false));
        } else if (rule instanceof Grammar.Include i) {
          expand(g, i.context(), out, visiting);
        } else if (rule instanceof Grammar.Embed e) {
          Grammar embedded = lookup.apply(e.grammar());
          if (embedded != null) {
            Alt end = Alt.of(e.end(), Kind.EMBED_END, null, -1, true);
            Context frame = add(g.id() + ":embed:" + e.grammar(), null, false, end, null);
            expand(embedded, Grammar.MAIN, frame.alts, new HashSet<String>());
            out.add(Alt.of(e.begin(), Kind.EMBED_BEGIN, e.token(), frame.id, false));
          }
        }
      }
      visiting.remove(name);
    }

    /**
     * Compiles a span's own context: escape, end, a nested begin, then the inner context's rules. A
     * template context leaves out an escape or end using {@code \1}, which is checked here with an
     * empty text.
     */
    int span(Grammar g, Grammar.Span s) {
      boolean template = templated(s.escape()) || templated(s.end());
      Context c =
          add(
              g.id() + ":span",
              s.token(),
              s.end() == null || !s.multiLine(),
              null,
              template ? s : null);
      c.nextLine = s.bodyNextLine();
      for (Pattern p : new Pattern[] {s.escape(), s.end()}) {
        if (templated(p)) {
          if (UNSUPPORTED.matcher(substitute(p, "").pattern()).find()) {
            throw new IllegalArgumentException("backreference or named group in " + p);
          }
        } else if (p != null) {
          boolean escape = p == s.escape();
          c.alts.add(
              escape
                  ? Alt.of(p, Kind.ESCAPE, TokenType.ESCAPE, -1, false)
                  : Alt.of(p, Kind.SPAN_END, s.token(), -1, true));
        }
      }
      if (s.nested()) {
        c.alts.add(Alt.of(s.begin(), Kind.SPAN_BEGIN, s.token(), c.id, false));
      }
      if (s.context() != null) {
        expand(g, s.context(), c.alts, new HashSet<String>());
      }
      return c.id;
    }
  }

  /** Builds a keyword rule's regex: the words, not preceded or followed by a word char. */
  static Pattern keywords(Grammar.Keywords k) {
    String word = "\\p{L}\\p{N}" + quoteClass(k.wordChars());
    TreeSet<Character> first = new TreeSet<Character>();
    TreeSet<String> words = new TreeSet<String>();
    for (String w : k.words()) {
      words.add(k.ignoreCase() ? GrammarSet.asciiLower(w) : w);
      char c = w.charAt(0);
      first.add(c);
      if (k.ignoreCase()) {
        first.add(Character.toUpperCase(c));
        first.add(Character.toLowerCase(c));
      }
    }
    String alternation = trie(words);
    StringBuilder firstClass = new StringBuilder();
    for (char c : first) {
      firstClass.append(quoteClass(String.valueOf(c)));
    }
    return Pattern.compile(
        "(?=["
            + firstClass
            + "])(?<!["
            + word
            + "])"
            + (k.ignoreCase() ? "(?i:" : "(?:")
            + alternation
            + ")(?!["
            + word
            + "])");
  }

  /**
   * Returns an alternation of distinct non-empty words as a prefix tree, so the regex engine
   * compares each char once per branch instead of once per word.
   */
  private static String trie(Set<String> words) {
    TreeMap<Character, TreeSet<String>> groups = new TreeMap<Character, TreeSet<String>>();
    for (String w : words) {
      TreeSet<String> rests = groups.get(w.charAt(0));
      if (rests == null) {
        rests = new TreeSet<String>();
        groups.put(w.charAt(0), rests);
      }
      rests.add(w.substring(1));
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<Character, TreeSet<String>> group : groups.entrySet()) {
      if (out.length() > 0) {
        out.append('|');
      }
      out.append(quoteClass(String.valueOf(group.getKey())));
      TreeSet<String> rests = group.getValue();
      boolean ends = rests.remove("");
      if (!rests.isEmpty()) {
        out.append("(?:").append(trie(rests)).append(ends ? ")?" : ")");
      }
    }
    return out.toString();
  }

  private static String quoteClass(String chars) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < chars.length(); i++) {
      char c = chars.charAt(i);
      if (!Character.isLetterOrDigit(c)) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }
}
