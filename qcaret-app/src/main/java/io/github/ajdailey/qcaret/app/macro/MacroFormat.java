package io.github.ajdailey.qcaret.app.macro;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.edit.CodecException;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import io.github.ajdailey.qcaret.core.edit.TextCodec;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The macro file text: a {@value #HEADER} line, then one step per line, with {@code #} comment
 * lines and blank lines allowed and LF or CRLF breaks. An optional {@code key Mod+Shift+1} line
 * ({@link KeyStroke#stored()}) gives the macro's key. Strings are quoted with JSON string escapes,
 * so control chars, CR, LF and surrogate pairs round-trip. One line that does not parse refuses the
 * whole text.
 *
 * <pre>
 * command CARET_DOWN
 * type "text"
 * insert "text"
 * complete "word"
 * find "text" forward|backward|incremental case=on word=off regex=off
 * replace "find" "with" case=off word=off regex=off
 * replace-all "find" "with" case=off word=off regex=off
 * go-to-line 120          (or 120:5 with a column)
 * tab-width 6
 * date-time "yyyy-MM-dd"
 * number-sequence start=1 step=1 pad=3 base=decimal|hexUpper|hexLower
 * </pre>
 *
 * A {@code command} line names a {@link Recordability#COMMAND} command.
 */
public final class MacroFormat {
  /** The first line. */
  public static final String HEADER = "qcaret-macro 1";

  /** A text that does not parse. */
  public static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;

    private final int line;

    /**
     * Creates the refusal.
     *
     * @param line the 1-based line
     * @param reason what is wrong
     */
    public Refusal(int line, String reason) {
      super("line " + line + ": " + reason);
      this.line = line;
    }

    /**
     * Returns the line that failed.
     *
     * @return from 1
     */
    public int line() {
      return line;
    }
  }

  private MacroFormat() {}

  /**
   * Writes a macro, LF line breaks; the name is not written.
   *
   * @param macro the macro
   * @return the text
   * @throws IllegalArgumentException when a string holds a lone surrogate, which no file keeps
   */
  public static String write(Macro macro) {
    StringBuilder out = new StringBuilder(HEADER).append('\n');
    if (macro.key() != null) {
      out.append("key ").append(macro.key().stored()).append('\n');
    }
    for (MacroStep step : macro.steps()) {
      out.append(line(step)).append('\n');
    }
    return out.toString();
  }

  private static String line(MacroStep step) {
    return switch (step) {
      case MacroStep.Command c -> "command " + c.command().name();
      case MacroStep.Typed t -> "type " + quote(t.text());
      case MacroStep.Insert i -> "insert " + quote(i.text());
      case MacroStep.Complete c -> "complete " + quote(c.word());
      case MacroStep.Find f ->
          "find " + quote(f.text()) + " " + f.direction().word() + " " + options(f.options());
      case MacroStep.Replace r ->
          (r.all() ? "replace-all " : "replace ")
              + quote(r.find())
              + " "
              + quote(r.with())
              + " "
              + options(r.options());
      case MacroStep.GoToLine g ->
          "go-to-line " + g.line() + (g.column() == 1 ? "" : ":" + g.column());
      case MacroStep.TabWidth w -> "tab-width " + w.columns();
      case MacroStep.DateTime d -> "date-time " + quote(d.pattern());
      case MacroStep.Sequence s ->
          "number-sequence start="
              + s.sequence().start()
              + " step="
              + s.sequence().step()
              + " pad="
              + s.sequence().width()
              + " base="
              + s.sequence().base().key();
    };
  }

  private static String options(MacroStep.Options o) {
    return "case="
        + onOff(o.matchCase())
        + " word="
        + onOff(o.wholeWord())
        + " regex="
        + onOff(o.regex());
  }

  private static String onOff(boolean on) {
    return on ? "on" : "off";
  }

  private static String quote(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean pairedHigh =
          Character.isHighSurrogate(c)
              && i + 1 < text.length()
              && Character.isLowSurrogate(text.charAt(i + 1));
      if (pairedHigh) {
        i++;
      } else if (Character.isSurrogate(c)) {
        throw new IllegalArgumentException("lone surrogate at " + i);
      }
    }
    try {
      return '"' + TextCodec.JSON_ESCAPE.convert(text, new CancellationToken()) + '"';
    } catch (CodecException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Parses a macro text.
   *
   * @param text the file's text
   * @param name the macro's name, or null
   * @return the macro
   * @throws Refusal when the header is missing, a line does not parse, or the text has no step
   */
  public static Macro parse(String text, String name) throws Refusal {
    String body = !text.isEmpty() && text.charAt(0) == '\ufeff' ? text.substring(1) : text;
    String[] lines = body.split("\r?\n", -1);
    if (!lines[0].strip().equals(HEADER)) {
      throw new Refusal(1, "the first line is not " + HEADER);
    }
    KeyStroke key = null;
    List<MacroStep> steps = new ArrayList<MacroStep>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      List<Token> tokens = tokens(line, i + 1);
      if ("key".equals(tokens.get(0).word())) {
        if (key != null || tokens.size() != 2 || tokens.get(1).quoted()) {
          throw new Refusal(i + 1, "one key line with one key");
        }
        key = KeyStroke.parse(tokens.get(1).text());
        if (key == null) {
          throw new Refusal(i + 1, "not a key: " + tokens.get(1).text());
        }
        continue;
      }
      try {
        steps.add(step(tokens, i + 1));
      } catch (IllegalArgumentException e) {
        throw new Refusal(i + 1, e.getMessage());
      }
    }
    if (steps.isEmpty()) {
      throw new Refusal(lines.length, "no steps");
    }
    return new Macro(name, key, steps);
  }

  /**
   * One token of a line.
   *
   * @param text the bare word, or the unescaped string
   * @param quoted true for a string
   */
  private record Token(String text, boolean quoted) {
    /** Returns the bare word, or null for a string. */
    String word() {
      return quoted ? null : text;
    }
  }

  private static List<Token> tokens(String line, int number) throws Refusal {
    List<Token> out = new ArrayList<Token>();
    int i = 0;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (c == ' ' || c == '\t') {
        i++;
        continue;
      }
      if (c != '"') {
        int end = i;
        while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != '\t') {
          end++;
        }
        out.add(new Token(line.substring(i, end), false));
        i = end;
        continue;
      }
      int end = i + 1;
      while (end < line.length() && line.charAt(end) != '"') {
        end += line.charAt(end) == '\\' ? 2 : 1;
      }
      if (end >= line.length()) {
        throw new Refusal(number, "unterminated string");
      }
      try {
        String inner = line.substring(i + 1, end);
        out.add(new Token(TextCodec.JSON_UNESCAPE.convert(inner, new CancellationToken()), true));
      } catch (CodecException e) {
        throw new Refusal(number, "bad escape in a string");
      }
      i = end + 1;
      if (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
        throw new Refusal(number, "no space after a string");
      }
    }
    return out;
  }

  private static MacroStep step(List<Token> t, int number) throws Refusal {
    String verb = t.get(0).word();
    if (verb == null) {
      throw new Refusal(number, "a line starts with a verb");
    }
    return switch (verb) {
      case "command" -> {
        count(t, 2, number);
        CommandId command = command(word(t, 1, number), number);
        yield new MacroStep.Command(command);
      }
      case "type" -> {
        count(t, 2, number);
        yield new MacroStep.Typed(string(t, 1, number));
      }
      case "insert" -> {
        count(t, 2, number);
        yield new MacroStep.Insert(string(t, 1, number));
      }
      case "complete" -> {
        count(t, 2, number);
        yield new MacroStep.Complete(string(t, 1, number));
      }
      case "find" -> {
        count(t, 6, number);
        yield new MacroStep.Find(
            string(t, 1, number), direction(word(t, 2, number), number), options(t, 3, number));
      }
      case "replace", "replace-all" -> {
        count(t, 6, number);
        yield new MacroStep.Replace(
            string(t, 1, number),
            string(t, 2, number),
            options(t, 3, number),
            verb.equals("replace-all"));
      }
      case "go-to-line" -> {
        count(t, 2, number);
        String[] parts = word(t, 1, number).split(":", -1);
        if (parts.length > 2) {
          throw new Refusal(number, "a position is line or line:column");
        }
        int line = number(parts[0], 1, Integer.MAX_VALUE, number);
        int column = parts.length == 2 ? number(parts[1], 1, Integer.MAX_VALUE, number) : 1;
        yield new MacroStep.GoToLine(line, column);
      }
      case "tab-width" -> {
        count(t, 2, number);
        yield new MacroStep.TabWidth(number(word(t, 1, number), 1, Document.MAX_TAB_WIDTH, number));
      }
      case "date-time" -> {
        count(t, 2, number);
        String pattern = string(t, 1, number);
        DateTimeFormatter.ofPattern(pattern);
        yield new MacroStep.DateTime(pattern);
      }
      case "number-sequence" -> {
        count(t, 5, number);
        long start = longValue(keyed(t, 1, "start", number), number);
        long step = longValue(keyed(t, 2, "step", number), number);
        int pad = number(keyed(t, 3, "pad", number), 0, NumberSequence.MAX_WIDTH, number);
        NumberSequence.Base base = NumberSequence.Base.parse(keyed(t, 4, "base", number));
        if (base == null) {
          throw new Refusal(number, "base is decimal, hexUpper or hexLower");
        }
        yield new MacroStep.Sequence(new NumberSequence(start, step, pad, base));
      }
      default -> throw new Refusal(number, "unknown step: " + verb);
    };
  }

  private static void count(List<Token> t, int expected, int number) throws Refusal {
    if (t.size() != expected) {
      throw new Refusal(number, t.get(0).text() + " takes " + (expected - 1) + " values");
    }
  }

  private static String word(List<Token> t, int i, int number) throws Refusal {
    if (t.get(i).quoted()) {
      throw new Refusal(number, "value " + i + " is a word, not a string");
    }
    return t.get(i).text();
  }

  private static String string(List<Token> t, int i, int number) throws Refusal {
    if (!t.get(i).quoted()) {
      throw new Refusal(number, "value " + i + " is a quoted string");
    }
    return t.get(i).text();
  }

  private static CommandId command(String name, int number) throws Refusal {
    CommandId command;
    try {
      command = CommandId.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new Refusal(number, "unknown command: " + name);
    }
    if (Recordability.of(command) != Recordability.COMMAND) {
      throw new Refusal(number, "not a recordable command: " + name);
    }
    return command;
  }

  private static MacroStep.Direction direction(String word, int number) throws Refusal {
    for (MacroStep.Direction d : MacroStep.Direction.values()) {
      if (d.word().equals(word)) {
        return d;
      }
    }
    throw new Refusal(number, "direction is forward, backward or incremental");
  }

  private static MacroStep.Options options(List<Token> t, int from, int number) throws Refusal {
    return new MacroStep.Options(
        on(keyed(t, from, "case", number), number),
        on(keyed(t, from + 1, "word", number), number),
        on(keyed(t, from + 2, "regex", number), number));
  }

  private static boolean on(String value, int number) throws Refusal {
    if (value.equals("on") || value.equals("off")) {
      return value.equals("on");
    }
    throw new Refusal(number, "an option is on or off");
  }

  /** Returns the value of a {@code key=value} word. */
  private static String keyed(List<Token> t, int i, String key, int number) throws Refusal {
    String w = word(t, i, number);
    if (!w.startsWith(key + "=")) {
      throw new Refusal(number, "value " + i + " is " + key + "=");
    }
    return w.substring(key.length() + 1);
  }

  private static int number(String text, int min, int max, int number) throws Refusal {
    long value = longValue(text, number);
    if (value < min || value > max) {
      throw new Refusal(number, text + " is outside " + min + " to " + max);
    }
    return (int) value;
  }

  private static long longValue(String text, int number) throws Refusal {
    if (!text.matches("-?[0-9]{1,19}")) {
      throw new Refusal(number, "not a number: " + text);
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw new Refusal(number, "not a 64-bit number: " + text);
    }
  }
}
