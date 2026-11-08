package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import org.junit.jupiter.api.Test;

/** Expand Selection's widening order, Select Line, and every literal occurrence. */
class SelectionExpansionTest {
  private static String expand(String marked, Brackets.Filter filter) {
    Marked m = new Marked(marked);
    SelectionSet set =
        SelectionExpansion.expand(
            m.document.text(),
            m.model.selections(),
            Pairs.DEFAULT,
            filter,
            Integer.MAX_VALUE,
            new CancellationToken());
    m.model.set(set);
    return m.marked();
  }

  private static String expand(String marked) {
    return expand(marked, Brackets.Filter.ALL);
  }

  @Test
  void repeatedExpansionWidensWordQuotesBracketsLineText() {
    String text = "x = f(a, \"b|c d\");\ny";
    String[] steps = {
      "x = f(a, \"[bc] d\");\ny",
      "x = f(a, \"[bc d]\");\ny",
      "x = f(a, [\"bc d\"]);\ny",
      "x = f([a, \"bc d\"]);\ny",
      "x = f[(a, \"bc d\")];\ny",
      "[x = f(a, \"bc d\");]\ny",
      "[x = f(a, \"bc d\");\ny]"
    };
    String at = text;
    for (String step : steps) {
      at = expand(at);
      assertEquals(step, at);
    }
    assertEquals(steps[steps.length - 1], expand(at));
  }

  @Test
  void eachSelectionExpandsOnItsOwn() {
    assertEquals("([ab]) {[cd]*}", expand("(a|b) {c|d*}"));
    assertEquals("[(ab)] {[cd]*}", expand("([ab]) {c|d*}"));
  }

  @Test
  void escapedQuotesAndOtherPairsDoNotEnclose() {
    assertEquals("'[x \\' y]'", expand("'x \\'| y'"));
    assertEquals("{[a ( b]}", expand("{a (| b}"));
  }

  @Test
  void bracketsTheFilterLeavesOutDoNotEnclose() {
    final String text = "f(x, \")\", y|)";
    Brackets.Filter strings =
        new Brackets.Filter() {
          @Override
          public boolean counts(int offset) {
            return offset != text.indexOf(")");
          }
        };
    assertEquals("f(x, \")\", [y])", expand(text, strings));
    assertEquals("f([x, \")\", y])", expand("f(x, \")\", [y])", strings));
  }

  @Test
  void aSearchStoppedByTheScanBoundReturnsNull() {
    Marked m = new Marked("(" + " ".repeat(100) + "|" + " ".repeat(100) + ")");
    assertNull(
        SelectionExpansion.expand(
            m.document.text(),
            m.model.selections(),
            Pairs.DEFAULT,
            Brackets.Filter.ALL,
            10,
            new CancellationToken()));
  }

  @Test
  void selectLineTakesWholeLinesThenTheNextOne() {
    Marked m = new Marked("a|b\r\ncd\r\nef");
    m.model.set(SelectionExpansion.lines(m.document.text(), m.model.selections()));
    assertEquals("[ab\r\n]cd\r\nef", m.marked());
    m.model.set(SelectionExpansion.lines(m.document.text(), m.model.selections()));
    assertEquals("[ab\r\ncd\r\n]ef", m.marked());
    m.model.set(SelectionExpansion.lines(m.document.text(), m.model.selections()));
    m.model.set(SelectionExpansion.lines(m.document.text(), m.model.selections()));
    assertEquals("[ab\r\ncd\r\nef]", m.marked());
    assertEquals("[a\n]b\n[c\n]*", lines("a|\nb\nc|*\n"));
  }

  private static String lines(String marked) {
    Marked m = new Marked(marked);
    m.model.set(SelectionExpansion.lines(m.document.text(), m.model.selections()));
    return m.marked();
  }

  @Test
  void allFindsEveryOccurrenceWithoutOverlapUpToTheMaximum() {
    TextContent text = new Marked("aaaa.aa").document.text();
    CancellationToken token = new CancellationToken();
    assertArrayEquals(new int[] {0, 2, 2, 4, 5, 7}, Occurrences.all(text, "aa", 10, token));
    assertArrayEquals(new int[] {0, 2, 2, 4}, Occurrences.all(text, "aa", 2, token));
    assertArrayEquals(new int[0], Occurrences.all(text, "b", 10, token));
  }
}
