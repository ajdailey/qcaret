package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The built-in grammars, read once, and tokenizing for assertions. */
final class Tokens {
  /** The built-in grammars. */
  static final GrammarSet BUILT_IN =
      GrammarSet.of(
          GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());

  private Tokens() {}

  /**
   * Tokenizes a text line by line.
   *
   * @param syntax the grammar
   * @param text lines separated by LF
   * @return one {@code TYPE:text} entry per token, in order
   */
  static List<String> of(Syntax syntax, String text) {
    Tokenizer tokenizer = syntax.tokenizer(SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
    TokenList list = new TokenList();
    List<String> out = new ArrayList<String>();
    int state = Tokenizer.INITIAL;
    for (String line : text.split("\n", -1)) {
      state = tokenizer.tokenize(line, state, list, new CancellationToken());
      int[] t = list.toArray();
      for (int i = 0; i < t.length; i += 3) {
        out.add(TokenType.of(t[i + 2]) + ":" + line.substring(t[i], t[i + 1]));
      }
    }
    return out;
  }

  /** Tokenizes with a built-in grammar. */
  static List<String> of(String grammar, String text) {
    return of(BUILT_IN.syntax(grammar), text);
  }

  /** Reads a grammar from its XML text. */
  static Grammar read(String xml) throws IOException, InvalidGrammarException {
    return new GrammarReader()
        .read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test.xml");
  }

  /** Compiles a grammar from its XML text against the built-in grammars. */
  static Syntax compile(String xml) throws IOException, InvalidGrammarException {
    return Syntax.compile(read(xml), BUILT_IN::get);
  }
}
