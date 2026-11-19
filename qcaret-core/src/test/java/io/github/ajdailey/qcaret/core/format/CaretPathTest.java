package io.github.ajdailey.qcaret.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.format.Formats.Language;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Copy XML Path and Copy JSON Path through {@link CaretPath}; {@code |} marks the caret. */
class CaretPathTest {
  private static CaretPath.Result find(Language language, String marked, boolean strict) {
    int caret = marked.indexOf('|');
    String text = marked.substring(0, caret) + marked.substring(caret + 1);
    return CaretPath.find(
        language, FormatFixture.document(text).text(), caret, strict, new CancellationToken());
  }

  private static String xml(String marked) {
    CaretPath.Result result = find(Language.XML, marked, false);
    assertNull(result.error(), marked);
    return result.path();
  }

  private static String json(String marked) {
    CaretPath.Result result = find(Language.JSON, marked, false);
    assertNull(result.error(), marked);
    return result.path();
  }

  private static final String XML =
      "<?xml version=\"1.0\"?>\n"
          + "<a>\n"
          + "  <b id=\"1\">one</b>\n"
          + "  <b id='2'><c>x</c><!-- note --><![CDATA[raw]]></b>\n"
          + "  <d/>\n"
          + "</a>\n";

  private static String at(String text, String before, String after) {
    int i = text.indexOf(before) + before.length();
    assertEquals(i, text.indexOf(after, i));
    return text.substring(0, i) + "|" + text.substring(i);
  }

  @Test
  void anXmlElementTakesAnIndexOnlyAmongSameNamedSiblings() {
    assertEquals("/a", xml(at(XML, "\n", "<a>")));
    assertEquals("/a", xml(at(XML, "<a", ">")));
    assertEquals("/a/b[1]", xml(at(XML, "  <", "b id=\"1\"")));
    assertEquals("/a/b[1]", xml(at(XML, ">o", "ne")));
    assertEquals("/a/b[1]", xml(at(XML, "one</", "b>")));
    assertEquals("/a/b[2]/c", xml(at(XML, "<c>", "x")));
    assertEquals("/a/b[2]", xml(at(XML, "</c>", "<!--")));
    assertEquals("/a/d", xml(at(XML, "<d", "/>")));
    assertEquals("/a", xml(at(XML, "<d/>\n", "</a>")));
  }

  @Test
  void anAttributeTakesItsNameAndCommentsAndCdataTheirElement() {
    assertEquals("/a/b[1]/@id", xml(at(XML, "<b ", "id=\"1\"")));
    assertEquals("/a/b[1]/@id", xml(at(XML, "id=\"", "1\"")));
    assertEquals("/a/b[2]/@id", xml(at(XML, "id='2", "'")));
    assertEquals("/a/b[2]", xml(at(XML, "<!-- no", "te")));
    assertEquals("/a/b[2]", xml(at(XML, "CDATA[r", "aw")));
  }

  @Test
  void outsideEveryElementThePathIsTheRoot() {
    assertEquals("/", xml("<?xml version=\"1|.0\"?>\n<a/>"));
    assertEquals("/", xml("<!-- c| -->\n<a/>"));
    assertEquals("/", xml("<a/>\n|"));
  }

  @Test
  void prefixesStayAsWrittenAndAFragmentCountsItsTopLevel() {
    assertEquals(
        "/x:a/x:b[2]",
        xml("<x:a xmlns:x=\"urn:mock\"><x:b/><x:b>|</x:b><y:b xmlns:y=\"urn:y\"/></x:a>"));
    assertEquals("/r[2]/s", xml("<r/>\n<r><s>|</s></r>\ntext"));
  }

  @Test
  void malformedXmlBeforeTheCaretIsAnErrorAndAfterItIsNot() {
    CaretPath.Result before = find(Language.XML, "<a><b></c>\n<d>|</d></a>", false);
    assertNull(before.path());
    assertEquals(1, before.error().line());
    assertEquals("/a/d", xml("<a><d>|</d><b></c></a>"));
    assertEquals("/a/b[1]", xml("<a><b>|</b><b></a>"));
    CaretPath.Result inside = find(Language.XML, "<a><b c=\"|", false);
    assertNull(inside.path());
  }

  private static final String JSON =
      "{\n"
          + "  \"a\": [10, {\"b\": true}, [\"x\"]],\n"
          + "  \"key with space\": null, // note\n"
          + "  \"it's\": {\"c\": \"v\"},\n"
          + "  \"$ok_1\": 2\n"
          + "}\n";

  @Test
  void aJsonMemberTakesItsNameAndAnElementItsIndexFromZero() {
    assertEquals("$", json(at(JSON, "", "{\n")));
    assertEquals("$.a", json(at(JSON, "\"", "a\"")));
    assertEquals("$.a", json(at(JSON, "\"a\"", ":")));
    assertEquals("$.a", json(at(JSON, "\"a\": ", "[10")));
    assertEquals("$.a[0]", json(at(JSON, "[1", "0")));
    assertEquals("$.a[0]", json(at(JSON, "[10", ",")));
    assertEquals("$.a", json(at(JSON, "[10,", " {")));
    assertEquals("$.a[1]", json(at(JSON, "[10, ", "{")));
    assertEquals("$.a[1].b", json(at(JSON, "{\"b\": t", "rue")));
    assertEquals("$.a[1].b", json(at(JSON, "true", "}")));
    assertEquals("$.a[1]", json(at(JSON, "true}", ",")));
    assertEquals("$.a[2][0]", json(at(JSON, "[\"", "x\"]")));
    assertEquals("$.a[2]", json(at(JSON, "\"x\"]", "]")));
    assertEquals("$.a", json(at(JSON, "\"x\"]]", ",")));
  }

  @Test
  void aNameThatIsNotAnIdentifierIsQuoted() {
    assertEquals("$['key with space']", json(at(JSON, "\"key ", "with")));
    assertEquals("$", json(at(JSON, "null, // n", "ote")));
    assertEquals("$['it\\'s'].c", json(at(JSON, "{\"c\": \"", "v\"")));
    assertEquals("$.$ok_1", json(at(JSON, "\"$ok_1\": ", "2")));
    assertEquals("$['a\\\\b\\n']", json("{\"a\\\\b\\n\": |1}"));
    assertEquals("$['\u00e9']", json("{\"\\u00e9\": |1}"));
    assertEquals("$['1a']", json("{\"1a\": |1}"));
    assertEquals("$['']", json("{\"\": |1}"));
  }

  @Test
  void aScalarRootAndTheGapsAroundTheValueAreTheRoot() {
    assertEquals("$", json("  |42  "));
    assertEquals("$", json("42  |"));
    assertEquals("$", json("{\"a\": 1}\n|"));
  }

  @Test
  void malformedJsonBeforeTheCaretIsAnErrorAndAfterItIsNot() {
    CaretPath.Result before = find(Language.JSON, "{\"a\": [1,, 2], \"b\": |3}", false);
    assertNull(before.path());
    assertEquals(1, before.error().line());
    assertEquals(10, before.error().column());
    assertEquals("$.a[1]", json("{\"a\": [1, |2,, 3"));
    assertEquals("$.a", json("{\"a\": |"));
  }

  @Test
  void commentsFollowTheStrictSetting() {
    String text = "{\n  // note\n  \"a\": |1\n}";
    assertEquals("$.a", json(text));
    CaretPath.Result strict = find(Language.JSON, text, true);
    assertNull(strict.path());
    assertEquals(2, strict.error().line());
    assertEquals("$", find(Language.JSON, "{\"a\": 1}| // after", true).path());
  }

  @Test
  void aCanceledSearchThrows() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    final StringBuilder big = new StringBuilder("[");
    for (int i = 0; i < 100_000; i++) {
      big.append("1,");
    }
    big.append("1]");
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            CaretPath.find(
                Language.JSON,
                FormatFixture.document(big.toString()).text(),
                big.length() - 1,
                false,
                token);
          }
        });
  }
}
