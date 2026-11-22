package io.github.ajdailey.qcaret.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The arguments {@code qcaret.cmd} hands over. The script starts Java with {@code
 * -Dqcaret.launcher=cmd} and no arguments, and puts the argument text of its own command line in
 * {@link #VARIABLE}. Java reads the environment in UTF-16, so a name outside the ANSI code page
 * arrives intact, and the Java launcher never expands an unquoted {@code *} or {@code ?}.
 */
public final class CmdArguments {
  /** The system property {@code qcaret.cmd} sets. */
  public static final String LAUNCHER = "qcaret.launcher";

  /** {@link #LAUNCHER}'s value under {@code qcaret.cmd}. */
  public static final String CMD = "cmd";

  /** The environment variable holding the argument text. */
  public static final String VARIABLE = "QCARET_CMDLINE";

  private CmdArguments() {}

  /**
   * Returns the launch's arguments.
   *
   * @param args the arguments Java received
   * @param launcher {@link #LAUNCHER}'s value, or null
   * @param env the environment
   * @return {@link #VARIABLE} split by {@link #split} when {@code launcher} is {@link #CMD}, {@code
   *     args} is empty and the variable is set; else {@code args}
   */
  public static String[] of(String[] args, String launcher, Map<String, String> env) {
    String text = env.get(VARIABLE);
    if (!CMD.equals(launcher) || args.length > 0 || text == null) {
      return args;
    }
    return split(text).toArray(new String[0]);
  }

  /**
   * Splits argument text by the Microsoft C runtime rules: space and tab separate arguments outside
   * double quotes; {@code ""} inside quotes is one literal quote; 2n backslashes before a quote are
   * n backslashes and the quote toggles quoting; 2n+1 backslashes before a quote are n backslashes
   * and a literal quote; other backslashes are literal.
   *
   * @param text the argument text, without the program name
   * @return the arguments
   */
  public static List<String> split(String text) {
    List<String> out = new ArrayList<String>();
    StringBuilder arg = new StringBuilder();
    boolean inArg = false;
    boolean quoted = false;
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\\') {
        int slashes = 0;
        while (i < n && text.charAt(i) == '\\') {
          slashes++;
          i++;
        }
        boolean quote = i < n && text.charAt(i) == '"';
        int kept = quote ? slashes / 2 : slashes;
        for (int k = 0; k < kept; k++) {
          arg.append('\\');
        }
        if (quote && (slashes & 1) != 0) {
          arg.append('"');
          i++;
        }
        inArg = true;
      } else if (c == '"') {
        if (quoted && i + 1 < n && text.charAt(i + 1) == '"') {
          arg.append('"');
          i += 2;
        } else {
          quoted = !quoted;
          i++;
        }
        inArg = true;
      } else if ((c == ' ' || c == '\t') && !quoted) {
        if (inArg) {
          out.add(arg.toString());
          arg.setLength(0);
          inArg = false;
        }
        i++;
      } else {
        arg.append(c);
        inArg = true;
        i++;
      }
    }
    if (inArg) {
      out.add(arg.toString());
    }
    return out;
  }
}
