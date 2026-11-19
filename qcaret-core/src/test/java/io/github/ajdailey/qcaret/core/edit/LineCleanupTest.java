package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Remove Blank Lines, Reverse Lines, the numeric sort, Trim Leading Whitespace and the indentation
 * conversions over selected line blocks or the whole text.
 */
class LineCleanupTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  /** U+1F600, a supplementary char. */
  private static final String GRIN = "😀";

  private static String blank(String marked) {
    Marked m = new Marked(marked);
    return m.apply(LineTransforms.removeBlank(m.document.text(), m.model.selections(), TOKEN))
        .marked();
  }

  private static String reverse(String marked) {
    Marked m = new Marked(marked);
    return m.apply(LineTransforms.reverse(m.document.text(), m.model.selections(), TOKEN)).marked();
  }

  private static String numeric(String marked) {
    Marked m = new Marked(marked);
    return m.apply(
            LineTransforms.sort(
                m.document.text(), m.model.selections(), LineTransforms.Order.NUMERIC, TOKEN))
        .marked();
  }

  private static String trimLeading(String marked) {
    Marked m = new Marked(marked);
    return m.apply(LineTransforms.trimLeading(m.document.text(), m.model.selections(), TOKEN))
        .marked();
  }

  private static String indent(String marked, boolean tabs) {
    Marked m = new Marked(marked);
    return m.apply(
            LineTransforms.convertIndent(m.document.text(), m.model.selections(), 4, tabs, TOKEN))
        .marked();
  }

  @Test
  void blankLinesIncludeSpacesAndTabsOnly() {
    assertEquals("a\n\tb\n|", blank("a\n\n  \n\tb\n \t\n|"));
    assertEquals("a|", blank("a\n  |"));
    assertEquals("|", blank("  \n|"));
    assertEquals("a\r\nb" + GRIN + "|", blank("a\r\n\r\n\t\r\nb" + GRIN + "|"));
    assertEquals("x\n[a\nb]\n\ny", blank("x\n[a\n\nb]\n\ny"));
    Marked m = new Marked("a|\nb");
    assertNull(LineTransforms.removeBlank(m.document.text(), m.model.selections(), TOKEN));
  }

  @Test
  void aBlankLineWhoseRemovalJoinsACrAndAnLfStays() {
    Marked m = new Marked("a\r[ \n]\nb");
    LineEditsTest.assertUnchanged(
        1, LineTransforms.removeBlank(m.document.text(), m.model.selections(), TOKEN));
  }

  @Test
  void reverseKeepsEachBreakInPlace() {
    assertEquals("c\r\nb\na|", reverse("a\r\nb\nc|"));
    assertEquals("ab\n" + GRIN + "|", reverse(GRIN + "\nab|"));
    assertEquals("x\n[b\na]\nz", reverse("x\n[a\nb]\nz"));
    Marked m = new Marked("a\nb\na|");
    assertNull(LineTransforms.reverse(m.document.text(), m.model.selections(), TOKEN));
  }

  @Test
  void aNumericSortPutsLinesWithoutANumberLastAndIsStable() {
    assertEquals(
        "-2.5\n.5\n+3\n 7 apples\n9b\n9a\n10\nx\nb" + GRIN + "|",
        numeric("10\n9b\n-2.5\nx\n+3\n.5\nb" + GRIN + "\n 7 apples\n9a|"));
    assertEquals("1.50\r\n1.5\r\n02|", numeric("02\r\n1.50\r\n1.5|"));
  }

  @Test
  void theNumericKeyIsASignDigitsAndAFraction() {
    assertEquals(new BigDecimal("12"), NumericKeys.key("12."));
    assertEquals(new BigDecimal("-0.25"), NumericKeys.key("\t-.25x"));
    assertEquals(new BigDecimal("1"), NumericKeys.key("1e5"));
    assertEquals(new BigDecimal("1"), NumericKeys.key("1,000"));
    assertEquals(
        new BigDecimal("123456789012345678901234567890"),
        NumericKeys.key("123456789012345678901234567890"));
    for (String none : new String[] {"", "-", ".", "+.", "x1", "١٢", "- 1"}) {
      assertNull(NumericKeys.key(none), none);
    }
  }

  @Test
  void trimLeadingPullsACaretInTheBlanksToTheLineStart() {
    assertEquals("a\nb|\nc", trimLeading(" \ta\n  b|\n c"));
    assertEquals("|x", trimLeading("  |  x"));
    assertEquals(GRIN + "\r\n|", trimLeading("\t" + GRIN + "\r\n|"));
    Marked m = new Marked("a|\nb");
    assertNull(LineTransforms.trimLeading(m.document.text(), m.model.selections(), TOKEN));
  }

  @Test
  void blanksWhoseLeadingTrimJoinsACrAndAnLfStay() {
    Marked m = new Marked("a\r  \nb|");
    LineEditsTest.assertUnchanged(
        1, LineTransforms.trimLeading(m.document.text(), m.model.selections(), TOKEN));
  }

  @Test
  void indentationConvertsAtTheTabWidth() {
    assertEquals(
        "      a\n      b\n    c\n x\r\n|", indent("\t  a\n      b\n  \tc\n x\r\n|", false));
    assertEquals("\t  a\n\t  b\n\tc\n x\r\n|", indent("\t  a\n      b\n  \tc\n x\r\n|", true));
    assertEquals("|\tx", indent("  |  x", true));
    assertEquals("\t|x", indent("    |x", true));
    assertEquals("x\n[\ta\n\tb]\n    c", indent("x\n[    a\n    b]\n    c", true));
    Marked m = new Marked("\ta\n  b|");
    assertNull(
        LineTransforms.convertIndent(m.document.text(), m.model.selections(), 4, true, TOKEN));
  }

  @Test
  void textsLongerThanTheCheckIntervalConvert() {
    StringBuilder text = new StringBuilder();
    StringBuilder blanksRemoved = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      text.append("\t").append(9_999 - i).append('\n').append("  \n");
      blanksRemoved.append("\t").append(9_999 - i).append('\n');
    }
    String marked = text + "|";
    assertEquals(blanksRemoved + "|", blank(marked));
    String sorted = numeric(blank(marked));
    assertEquals("\t0\n\t1\n", sorted.substring(0, 6));
    assertEquals(blanksRemoved + "|", reverse(sorted));
    String spaces = indent(marked, false);
    assertEquals(text.toString().replace("\t", "    ") + "|", spaces);
    assertEquals(marked, indent(spaces, true));
  }

  @Test
  void eachOperationIsOneCommandStep() {
    Marked m = new Marked("  b\n\n\ta|");
    Edit edit = LineTransforms.removeBlank(m.document.text(), m.model.selections(), TOKEN);
    assertEquals(EditBatch.Kind.COMMAND, edit.batch().kind());
    m.apply(edit);
    m.apply(LineTransforms.reverse(m.document.text(), m.model.selections(), TOKEN));
    m.apply(LineTransforms.trimLeading(m.document.text(), m.model.selections(), TOKEN));
    assertEquals("a\nb|", m.marked());
    m.document.undo(m.model);
    m.document.undo(m.model);
    m.document.undo(m.model);
    assertEquals("  b\n\n\ta|", m.marked());
  }

  @Test
  void aCanceledTokenStopsEachOperation() {
    final Marked m = new Marked("  b\n\n\ta|");
    final CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    List<Executable> runs = new ArrayList<Executable>();
    runs.add(
        new Executable() {
          @Override
          public void execute() {
            LineTransforms.removeBlank(m.document.text(), m.model.selections(), canceled);
          }
        });
    runs.add(
        new Executable() {
          @Override
          public void execute() {
            LineTransforms.reverse(m.document.text(), m.model.selections(), canceled);
          }
        });
    runs.add(
        new Executable() {
          @Override
          public void execute() {
            LineTransforms.trimLeading(m.document.text(), m.model.selections(), canceled);
          }
        });
    runs.add(
        new Executable() {
          @Override
          public void execute() {
            LineTransforms.convertIndent(
                m.document.text(), m.model.selections(), 4, true, canceled);
          }
        });
    for (Executable run : runs) {
      assertThrows(CancellationException.class, run);
    }
  }

  @Test
  void aNumericSortCanceledMidRunStops() {
    final CancellationToken token = new CancellationToken();
    final List<String> lines =
        new AbstractList<String>() {
          @Override
          public String get(int index) {
            if (index == 5_000) {
              token.cancel();
            }
            return Integer.toString(index);
          }

          @Override
          public int size() {
            return 10_000;
          }
        };
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            NumericKeys.sort(lines, token);
          }
        });
  }
}
