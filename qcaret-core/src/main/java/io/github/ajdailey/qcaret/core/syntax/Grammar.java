package io.github.ajdailey.qcaret.core.syntax;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One language's grammar as its file declares it, regexes compiled. Immutable; {@link
 * GrammarReader} builds it and {@link Syntax#compile} turns it into a tokenizer.
 *
 * @param id the identifier, which a user grammar with the same id replaces
 * @param name the name menus show
 * @param version the grammar's own version
 * @param extensions file extensions without the dot, lower case
 * @param fileNames whole file names, matched ignoring case
 * @param firstLine matched against a file's first line, or null
 * @param comments the comment delimiters
 * @param brackets bracket pairs, each two strings
 * @param autoClose pairs typing closes, each two strings
 * @param indentIncrease a line matching it indents the next one, or null
 * @param indentDecrease a line matching it outdents itself, or null
 * @param indentDedentNext a line matching it outdents the line after it, or null
 * @param folding how fold regions are found
 * @param contexts the contexts by id; {@link #MAIN} starts every file
 */
public record Grammar(
    String id,
    String name,
    String version,
    List<String> extensions,
    List<String> fileNames,
    Pattern firstLine,
    Comments comments,
    List<String[]> brackets,
    List<String[]> autoClose,
    Pattern indentIncrease,
    Pattern indentDecrease,
    Pattern indentDedentNext,
    Folding folding,
    Map<String, List<Rule>> contexts) {
  /** The context every file starts in. */
  public static final String MAIN = "main";

  /** Copies the lists and checks that {@link #MAIN} exists. */
  public Grammar {
    extensions = List.copyOf(extensions);
    fileNames = List.copyOf(fileNames);
    brackets = List.copyOf(brackets);
    autoClose = List.copyOf(autoClose);
    contexts = Map.copyOf(contexts);
    if (!contexts.containsKey(MAIN)) {
      throw new IllegalArgumentException("grammar " + id + " has no context " + MAIN);
    }
  }

  /**
   * Comment delimiters; each is null when the language has none.
   *
   * @param line starts a line comment
   * @param blockStart opens a block comment
   * @param blockEnd closes a block comment
   */
  public record Comments(String line, String blockStart, String blockEnd) {
    /** No comments. */
    public static final Comments NONE = new Comments(null, null, null);

    /**
     * Reports whether block comments exist.
     *
     * @return true when both block delimiters are set
     */
    public boolean hasBlock() {
      return blockStart != null && blockEnd != null;
    }
  }

  /** How a grammar finds fold regions. */
  public enum FoldMode {
    /** No regions. */
    NONE,
    /** Open and close bracket chars outside comments and strings. */
    BRACKETS,
    /** Begin and end regex matches outside comments and strings. */
    MARKERS,
    /** By indentation, which the editor computes. */
    INDENT
  }

  /**
   * Fold settings.
   *
   * @param mode the mode
   * @param open the opening bracket chars, for {@link FoldMode#BRACKETS}
   * @param close the closing bracket chars
   * @param begin a match opens a region, for {@link FoldMode#MARKERS}
   * @param end a match closes a region
   */
  public record Folding(FoldMode mode, String open, String close, Pattern begin, Pattern end) {
    /** No folding. */
    public static final Folding NONE = new Folding(FoldMode.NONE, "", "", null, null);
  }

  /** One rule of a context, tried in order at each position; the earliest match wins. */
  public sealed interface Rule permits Match, Keywords, Span, Include, Embed {}

  /**
   * A regex that colors its match.
   *
   * @param regex the regex
   * @param token the match's token, or null for plain
   * @param push a context entered after the match, or null
   * @param pop true to leave the current context after the match
   */
  public record Match(Pattern regex, TokenType token, String push, boolean pop) implements Rule {}

  /**
   * Words that color as one token.
   *
   * @param words the words
   * @param token their token
   * @param ignoreCase true to match regardless of case
   * @param wordChars chars besides letters and digits that a word holds
   */
  public record Keywords(List<String> words, TokenType token, boolean ignoreCase, String wordChars)
      implements Rule {
    /** Copies the words. */
    public Keywords {
      words = List.copyOf(words);
    }
  }

  /**
   * Text between a begin and an end match, such as a string or a comment.
   *
   * @param begin opens the span
   * @param end closes it; null runs to the line's end
   * @param escape matched before {@code end} inside the span, colored as {@link TokenType#ESCAPE},
   *     or null
   * @param token the span's token, the delimiters included
   * @param context rules applied inside the span, or null
   * @param multiLine true when the span continues past a line's end
   */
  public record Span(
      Pattern begin,
      Pattern end,
      Pattern escape,
      TokenType token,
      String context,
      boolean multiLine)
      implements Rule {}

  /**
   * Another context's rules, in place.
   *
   * @param context the context's id
   */
  public record Include(String context) implements Rule {}

  /**
   * Another grammar between a begin and an end match, such as a script in HTML. The end is checked
   * in every context of the embedded grammar.
   *
   * @param grammar the embedded grammar's id
   * @param begin enters the embedded grammar after its match
   * @param end leaves it before its match
   * @param token the delimiters' token, or null
   */
  public record Embed(String grammar, Pattern begin, Pattern end, TokenType token)
      implements Rule {}
}
