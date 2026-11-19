package io.github.ajdailey.qcaret.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@link Formats} on a text and checks the whitespace-only contract: the output's tokens equal
 * the input's, and the two are equal with every whitespace char removed.
 */
final class FormatFixture {
  static final FormatLayout TWO = new FormatLayout("  ", "\n", false, 1 << 26);
  static final FormatLayout STRICT = new FormatLayout("  ", "\n", true, 1 << 26);

  private FormatFixture() {}

  static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  /** Runs on the whole text with a caret at 0. */
  static FormatResult result(
      Formats.Language language, Formats.Mode mode, String text, FormatLayout layout) {
    return Formats.run(
        language,
        mode,
        document(text).text(),
        SelectionSet.caret(0),
        layout,
        new CancellationToken());
  }

  /** Formats the whole text, which must not fail, and returns the text after the edit. */
  static String format(
      Formats.Language language, Formats.Mode mode, String text, FormatLayout layout) {
    Document document = document(text);
    FormatResult result =
        Formats.run(
            language,
            mode,
            document.text(),
            SelectionSet.caret(0),
            layout,
            new CancellationToken());
    assertNull(result.error(), String.valueOf(result.error()));
    if (result.batch() != null) {
      document.apply(result.batch());
    }
    String out = document.text().toString();
    assertWhitespaceOnly(language, text, out);
    return out;
  }

  /** Formats the given selections and returns the text after the edit. */
  static String formatSelections(
      Formats.Language language,
      Formats.Mode mode,
      String text,
      FormatLayout layout,
      Selection... selections) {
    Document document = document(text);
    FormatResult result =
        Formats.run(
            language,
            mode,
            document.text(),
            SelectionSet.of(List.of(selections), 0),
            layout,
            new CancellationToken());
    assertNull(result.error(), String.valueOf(result.error()));
    if (result.batch() != null) {
      document.apply(result.batch());
    }
    return document.text().toString();
  }

  /** Runs on the whole text, which must fail, and returns the error. */
  static FormatError error(
      Formats.Language language, Formats.Mode mode, String text, FormatLayout layout) {
    FormatResult result = result(language, mode, text, layout);
    assertNull(result.batch());
    assertNotNull(result.error(), "an error");
    return result.error();
  }

  static void assertWhitespaceOnly(Formats.Language language, String in, String out) {
    assertEquals(in.replaceAll("[ \t\r\n]", ""), out.replaceAll("[ \t\r\n]", ""));
    if (language == Formats.Language.XML) {
      assertEquals(xmlTokens(in), xmlTokens(out));
    } else {
      assertEquals(jsonTokens(in), jsonTokens(out));
    }
  }

  /** Splits XML into markup and text, dropping whitespace-only text. */
  static List<String> xmlTokens(String s) {
    List<String> tokens = new ArrayList<String>();
    int i = 0;
    while (i < s.length()) {
      int end;
      if (s.startsWith("<!--", i)) {
        end = s.indexOf("-->", i) + 3;
      } else if (s.startsWith("<![CDATA[", i)) {
        end = s.indexOf("]]>", i) + 3;
      } else if (s.startsWith("<!DOCTYPE", i)) {
        end = i;
        int brackets = 0;
        while (brackets > 0 || s.charAt(end) != '>') {
          char c = s.charAt(end);
          if (c == '"' || c == '\'') {
            end = s.indexOf(c, end + 1);
          } else if (s.startsWith("<!--", end)) {
            end = s.indexOf("-->", end) + 2;
          } else if (c == '[') {
            brackets++;
          } else if (c == ']') {
            brackets--;
          }
          end++;
        }
        end++;
      } else if (s.charAt(i) == '<') {
        end = i + 1;
        char quote = 0;
        while (quote != 0 || s.charAt(end) != '>') {
          char c = s.charAt(end);
          if (quote == 0 && (c == '"' || c == '\'')) {
            quote = c;
          } else if (c == quote) {
            quote = 0;
          }
          end++;
        }
        end++;
      } else {
        end = s.indexOf('<', i);
        end = end < 0 ? s.length() : end;
      }
      String token = s.substring(i, end);
      if (!token.isBlank()) {
        tokens.add(token);
      }
      i = end;
    }
    return tokens;
  }

  /** Splits JSON into strings, comments, punctuation and other runs. */
  static List<String> jsonTokens(String s) {
    List<String> tokens = new ArrayList<String>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int end;
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      } else if (c == '"') {
        end = i + 1;
        while (s.charAt(end) != '"') {
          end += s.charAt(end) == '\\' ? 2 : 1;
        }
        end++;
      } else if (s.startsWith("//", i)) {
        end = i;
        while (end < s.length() && s.charAt(end) != '\n' && s.charAt(end) != '\r') {
          end++;
        }
      } else if (s.startsWith("/*", i)) {
        end = s.indexOf("*/", i) + 2;
      } else if ("{}[]:,".indexOf(c) >= 0) {
        end = i + 1;
      } else {
        end = i;
        while (end < s.length() && " \t\r\n{}[]:,\"/".indexOf(s.charAt(end)) < 0) {
          end++;
        }
      }
      tokens.add(s.substring(i, end));
      i = end;
    }
    return tokens;
  }
}
