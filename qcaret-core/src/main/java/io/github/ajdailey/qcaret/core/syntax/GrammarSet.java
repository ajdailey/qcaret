package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The grammars in use, compiled, and language detection. Immutable and thread-safe.
 *
 * <p>Detection takes, in order: a grammar naming the file name, a grammar naming the extension (the
 * text after the last dot, any case), a grammar whose first-line regex matches the file's first
 * {@value #FIRST_LINE_CHARS} chars, then {@link #PLAIN}. User grammars are tried before built-in
 * ones.
 */
public final class GrammarSet {
  /** The id of plain text, which has no rules. */
  public static final String PLAIN = "plain";

  /** The built-in grammars' ids, each a resource {@code grammars/<id>.xml} beside this class. */
  public static final List<String> BUILT_IN =
      List.of(
          PLAIN,
          "java",
          "xml",
          "html",
          "json",
          "yaml",
          "sql",
          "python",
          "javascript",
          "typescript",
          "c",
          "cpp",
          "shell",
          "powershell",
          "batch",
          "properties",
          "ini",
          "css",
          "markdown");

  /** Chars of the first line the first-line regexes see. */
  public static final int FIRST_LINE_CHARS = 1000;

  private final Map<String, Grammar> grammars;
  private final Map<String, Syntax> compiled;
  private final List<Grammar> detection;

  private GrammarSet(
      Map<String, Grammar> grammars, Map<String, Syntax> compiled, List<Grammar> detection) {
    this.grammars = grammars;
    this.compiled = compiled;
    this.detection = detection;
  }

  /**
   * Reads the built-in grammars.
   *
   * @param reader the reader
   * @return the grammars in {@link #BUILT_IN} order
   * @throws IllegalStateException when a built-in grammar is missing or invalid
   */
  @WorkerThread
  public static List<Grammar> readBuiltIns(GrammarReader reader) {
    List<Grammar> out = new ArrayList<Grammar>();
    for (String id : BUILT_IN) {
      String name = "grammars/" + id + ".xml";
      try (InputStream in = GrammarSet.class.getResourceAsStream(name)) {
        if (in == null) {
          throw new IllegalStateException("missing built-in grammar " + name);
        }
        out.add(reader.read(in, name));
      } catch (IOException | InvalidGrammarException e) {
        throw new IllegalStateException("built-in grammar " + name + " failed", e);
      }
    }
    return out;
  }

  /**
   * Builds the set: user grammars replace built-in ones with the same id; each grammar compiles,
   * and a user grammar that does not is left out with a message.
   *
   * @param builtIns the built-in grammars
   * @param users the user's grammars, each with the file it came from
   * @param errors receives one message per user grammar left out
   * @return the set
   */
  @WorkerThread
  public static GrammarSet of(
      List<Grammar> builtIns, Map<String, Grammar> users, List<String> errors) {
    Map<String, Grammar> builtIn = new LinkedHashMap<String, Grammar>();
    for (Grammar g : builtIns) {
      builtIn.put(g.id(), g);
    }
    final Map<String, Grammar> all = new LinkedHashMap<String, Grammar>(builtIn);
    for (Grammar g : users.values()) {
      all.put(g.id(), g);
    }
    Function<String, Grammar> find =
        new Function<String, Grammar>() {
          @Override
          public Grammar apply(String id) {
            return all.get(id);
          }
        };
    Map<String, Syntax> compiled = new LinkedHashMap<String, Syntax>();
    List<Grammar> userGrammars = new ArrayList<Grammar>();
    for (Map.Entry<String, Grammar> user : users.entrySet()) {
      Grammar g = user.getValue();
      try {
        compiled.put(g.id(), Syntax.compile(g, find));
        userGrammars.add(g);
      } catch (InvalidGrammarException e) {
        errors.add(user.getKey() + ": " + e.getMessage());
        if (builtIn.containsKey(g.id())) {
          all.put(g.id(), builtIn.get(g.id()));
        } else {
          all.remove(g.id());
        }
      }
    }
    for (Grammar g : all.values()) {
      if (!compiled.containsKey(g.id())) {
        try {
          compiled.put(g.id(), Syntax.compile(g, find));
        } catch (InvalidGrammarException e) {
          throw new IllegalStateException("built-in grammar " + g.id() + " failed", e);
        }
      }
    }
    List<Grammar> detection = new ArrayList<Grammar>(userGrammars);
    for (Grammar g : all.values()) {
      if (!detection.contains(g)) {
        detection.add(g);
      }
    }
    return new GrammarSet(
        Collections.unmodifiableMap(all),
        Collections.unmodifiableMap(compiled),
        List.copyOf(detection));
  }

  /**
   * Returns a text with ASCII capitals in lower case; other chars stay, as file names and
   * extensions match ignoring ASCII case only.
   *
   * @param text the text
   * @return the text in lower case
   */
  static String asciiLower(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return out.toString();
  }

  /**
   * Returns a grammar.
   *
   * @param id the id
   * @return the grammar, or null
   */
  public Grammar get(String id) {
    return grammars.get(id);
  }

  /**
   * Returns a grammar compiled.
   *
   * @param id the id
   * @return the syntax, or null for an unknown id
   */
  public Syntax syntax(String id) {
    return compiled.get(id);
  }

  /**
   * Returns every grammar for a menu: plain text first, then by name.
   *
   * @return the grammars
   */
  public List<Grammar> byName() {
    List<Grammar> out = new ArrayList<Grammar>(grammars.values());
    out.sort(
        new Comparator<Grammar>() {
          @Override
          public int compare(Grammar a, Grammar b) {
            if (a.id().equals(PLAIN) != b.id().equals(PLAIN)) {
              return a.id().equals(PLAIN) ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
          }
        });
    return out;
  }

  /**
   * Picks the grammar for a file.
   *
   * @param fileName the file's name without its folder, or null for an untitled document
   * @param firstLine the file's first line, or empty
   * @param cancel checked while a first-line regex reads
   * @return the grammar's id, {@link #PLAIN} when none matches
   */
  @WorkerThread
  public String detect(String fileName, CharSequence firstLine, CancellationToken cancel) {
    if (fileName != null) {
      for (Grammar g : detection) {
        for (String name : g.fileNames()) {
          if (asciiLower(name).equals(asciiLower(fileName))) {
            return g.id();
          }
        }
      }
      int dot = fileName.lastIndexOf('.');
      if (dot >= 0 && dot < fileName.length() - 1) {
        String extension = asciiLower(fileName.substring(dot + 1));
        for (Grammar g : detection) {
          if (g.extensions().contains(extension)) {
            return g.id();
          }
        }
      }
    }
    CharSequence line =
        firstLine.length() > FIRST_LINE_CHARS
            ? firstLine.subSequence(0, FIRST_LINE_CHARS)
            : firstLine;
    BoundedChars chars = new BoundedChars();
    for (Grammar g : detection) {
      if (g.firstLine() == null) {
        continue;
      }
      try {
        if (g.firstLine().matcher(chars.reset(line, BoundedChars.lineLimit(line), cancel)).find()) {
          return g.id();
        }
      } catch (BoundedChars.LimitReached | StackOverflowError e) {
        // A first-line regex that runs away does not match.
      }
    }
    return PLAIN;
  }
}
