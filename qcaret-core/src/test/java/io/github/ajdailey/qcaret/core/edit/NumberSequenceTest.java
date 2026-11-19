package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Insert Number Sequence's values through {@link NumberSequence}. */
class NumberSequenceTest {
  private static final NumberSequence.Base DECIMAL = NumberSequence.Base.DECIMAL;

  private static List<String> values(long start, long step, int width, NumberSequence.Base base) {
    return new NumberSequence(start, step, width, base).values(4);
  }

  @Test
  void decimalCountsFromTheStartBySteps() {
    assertEquals(List.of("1", "2", "3", "4"), values(1, 1, 0, NumberSequence.Base.DECIMAL));
    assertEquals(List.of("10", "5", "0", "-5"), values(10, -5, 0, NumberSequence.Base.DECIMAL));
    assertEquals(List.of("7", "7", "7", "7"), values(7, 0, 0, NumberSequence.Base.DECIMAL));
    assertEquals(List.of(), new NumberSequence(1, 1, 0, NumberSequence.Base.DECIMAL).values(0));
  }

  @Test
  void paddingCountsTheSignAsPrintfDoes() {
    assertEquals(List.of("008", "009", "010", "011"), values(8, 1, 3, NumberSequence.Base.DECIMAL));
    assertEquals(
        List.of("-02", "-01", "000", "001"), values(-2, 1, 3, NumberSequence.Base.DECIMAL));
    assertEquals(List.of("1000", "1001", "1002", "1003"), values(1000, 1, 2, DECIMAL));
  }

  @Test
  void hexIsSignAndMagnitudeInEitherCase() {
    assertEquals(
        List.of("FE", "FF", "100", "101"), values(254, 1, 0, NumberSequence.Base.HEX_UPPER));
    assertEquals(
        List.of("00fe", "00ff", "0100", "0101"), values(254, 1, 4, NumberSequence.Base.HEX_LOWER));
    assertEquals(
        List.of("-0A", "-05", "000", "005"), values(-10, 5, 3, NumberSequence.Base.HEX_UPPER));
    assertEquals(
        "-8000000000000000",
        new NumberSequence(0, 0, 0, NumberSequence.Base.HEX_LOWER).format(Long.MIN_VALUE));
  }

  @Test
  void aNumberOutsideLongIsRefused() {
    final NumberSequence up = new NumberSequence(Long.MAX_VALUE - 1, 1, 0, DECIMAL);
    assertEquals(List.of("9223372036854775806", "9223372036854775807"), up.values(2));
    assertThrows(
        ArithmeticException.class,
        new Executable() {
          @Override
          public void execute() {
            up.values(3);
          }
        });
    final NumberSequence down = new NumberSequence(Long.MIN_VALUE, -1, 0, DECIMAL);
    assertThrows(
        ArithmeticException.class,
        new Executable() {
          @Override
          public void execute() {
            down.values(2);
          }
        });
  }

  @Test
  void fieldsParseDecimalOrHexWithinLong() {
    assertEquals(42L, NumberSequence.parse(" 42 "));
    assertEquals(-42L, NumberSequence.parse("-42"));
    assertEquals(255L, NumberSequence.parse("0xFF"));
    assertEquals(-255L, NumberSequence.parse("-0Xff"));
    assertEquals(Long.MAX_VALUE, NumberSequence.parse("9223372036854775807"));
    assertEquals(Long.MIN_VALUE, NumberSequence.parse("-9223372036854775808"));
    assertNull(NumberSequence.parse("9223372036854775808"));
    assertNull(NumberSequence.parse("-0x8000000000000001"));
    assertNull(NumberSequence.parse(""));
    assertNull(NumberSequence.parse("-"));
    assertNull(NumberSequence.parse("0x"));
    assertNull(NumberSequence.parse("+1"));
    assertNull(NumberSequence.parse("1.5"));
    assertNull(NumberSequence.parse("١"));
    assertNull(NumberSequence.parse("FF"));
  }

  @Test
  void aWidthOutsideItsRangeIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new NumberSequence(1, 1, NumberSequence.MAX_WIDTH + 1, DECIMAL);
          }
        });
    assertEquals(NumberSequence.Base.HEX_UPPER, NumberSequence.Base.parse("hexUpper"));
    assertNull(NumberSequence.Base.parse("octal"));
  }
}
