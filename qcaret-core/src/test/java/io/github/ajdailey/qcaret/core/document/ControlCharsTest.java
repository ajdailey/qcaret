package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The control chars drawn as boxes, their two columns, their table cell text and the block edge
 * rule across them.
 */
class ControlCharsTest {
  @Test
  void visibleShowsEachControlAsItsHexInAngleBrackets() {
    String plain = "tab\tand text";
    assertSame(plain, ControlChars.visible(plain));
    assertEquals("<1B>[0m<00>x<7F><9D>", ControlChars.visible("\u001b[0m\u0000x\u007f\u009d"));
    assertEquals("a<07>\t", ControlChars.visible("a\u0007\t"));
  }

  @Test
  void visibleOffsetLandsOnTheSameCharInTheVisibleText() {
    String text = "\u001bab\u0007c\u0081";
    String shown = ControlChars.visible(text);
    for (int i = 0; i < text.length(); i++) {
      int at = ControlChars.visibleOffset(text, i);
      char c = text.charAt(i);
      String expected = ControlChars.boxed(c) ? "<" + ControlChars.label(c) + ">" : "" + c;
      assertEquals(expected, shown.substring(at, at + expected.length()), String.valueOf(i));
    }
    assertEquals(shown.length(), ControlChars.visibleOffset(text, text.length()));
    int start = ControlChars.visibleOffset(text, 1);
    assertEquals("ab<07>", shown.substring(start, ControlChars.visibleOffset(text, 4)));
  }

  private static TextSnapshot text(String chars) {
    return Document.of(
            chars,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS)
        .text();
  }

  @Test
  void c0ExceptTabDelAndC1AreControlsAndNothingElse() {
    for (int c = 0; c < 0x20; c++) {
      assertEquals(c != '\t', ControlChars.boxed((char) c), Integer.toHexString(c));
    }
    for (int c = 0x20; c < 0x7f; c++) {
      assertFalse(ControlChars.boxed((char) c), Integer.toHexString(c));
    }
    for (int c = 0x7f; c <= 0x9f; c++) {
      assertTrue(ControlChars.boxed((char) c), Integer.toHexString(c));
    }
    for (int c : new int[] {0xa0, 0xad, 0x2028, 0x2029, 0xfeff, ControlChars.PLACEHOLDER}) {
      assertFalse(ControlChars.boxed((char) c), Integer.toHexString(c));
    }
  }

  @Test
  void theFiveUndefinedWindows1252BytesAndLineBreakCharsAreControls() {
    for (int c : new int[] {0x81, 0x8d, 0x8f, 0x90, 0x9d, '\r', '\n', 0x85}) {
      assertTrue(ControlChars.boxed((char) c), Integer.toHexString(c));
      assertEquals(2, Columns.width(c));
    }
  }

  @Test
  void hexIsTwoUppercaseDigits() {
    assertEquals("00", ControlChars.label('\u0000'));
    assertEquals("1B", ControlChars.label('\u001b'));
    assertEquals("7F", ControlChars.label('\u007f'));
    assertEquals("81", ControlChars.label('\u0081'));
    assertEquals("9F", ControlChars.label('\u009f'));
  }

  @Test
  void shownReplacesOnlyControlsAndKeepsALineWithoutThem() {
    String plain = "plain\ttext";
    assertSame(plain, ControlChars.shown(plain));
    char p = ControlChars.PLACEHOLDER;
    assertEquals("a" + p + "\t" + p + "b" + p, ControlChars.shown("a\u0001\t\u0081b\u007f"));
  }

  @Test
  void aControlTakesTwoColumnsAndAColumnInsideItRoundsToAnEdge() {
    String line = "a\u0001b\t\u0081";
    assertEquals(1, Columns.column(line, 1, 4));
    assertEquals(3, Columns.column(line, 2, 4));
    assertEquals(4, Columns.column(line, 3, 4));
    assertEquals(8, Columns.column(line, 4, 4));
    assertEquals(10, Columns.column(line, 5, 4));
    assertEquals(1, Columns.index(line, 2, 4, false));
    assertEquals(2, Columns.index(line, 2, 4, true));
    assertEquals(2, Columns.index(line, 3, 4, false));
  }

  @Test
  void aBlockEdgeInsideABoxTakesTheBoxWhole() {
    TextSnapshot text = text("a\u0001b\nabcd\n");
    List<BlockRow> rows = new BlockSelection(0, 2, 1, 3).rows(text, 4);
    assertEquals(1, rows.get(0).start());
    assertEquals(2, rows.get(0).end());
    rows = new BlockSelection(0, 2, 1, 4).rows(text, 4);
    assertEquals(1, rows.get(0).start());
    assertEquals(3, rows.get(0).end());
  }

  @Test
  void aLoneCrEndsALineSoNoBreakCharSitsInsideOne() {
    TextSnapshot text = text("a\rb\nc\r\nd");
    assertEquals(4, text.lineCount());
    assertEquals(1, text.lineLength(0));
    assertEquals(1, text.lineLength(2));
  }
}
