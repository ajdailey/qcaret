package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.util.BitSet;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class EncodedHexSourceTest {
  private static EncodedHexSource source(String text, TextEncoding encoding) {
    TextSnapshot snapshot = RopeStorage.of(text).snapshot();
    return new EncodedHexSource(ByteOffsets.build(snapshot, encoding, new CancellationToken()));
  }

  private static byte[] read(HexSource source, long position, int length, BitSet replaced)
      throws IOException {
    byte[] bytes = new byte[length];
    source.read(position, bytes, length, replaced);
    return bytes;
  }

  @Test
  void eachReadEqualsASliceOfAWholeEncode() throws IOException {
    String text = HexFixtures.text(2 * ByteOffsets.BLOCK_CHARS + 3000, 23);
    for (TextEncoding encoding : ByteOffsetsTest.encodings()) {
      String name = encoding.charset().name() + (encoding.bom() ? " BOM" : "");
      EncodedHexSource source = source(text, encoding);
      byte[] whole = HexFixtures.wholeEncode(text, encoding);
      assertEquals(whole.length, source.size(), name);
      assertArrayEquals(whole, read(source, 0, whole.length, null), name);
      SplittableRandom random = new SplittableRandom(23);
      for (int i = 0; i < 30; i++) {
        int position = random.nextInt(whole.length);
        int length =
            Math.min(whole.length - position, 1 + random.nextInt(2 * HexBlocks.BLOCK_BYTES));
        assertArrayEquals(
            HexFixtures.slice(whole, position, length),
            read(source, position, length, null),
            name + " at " + position);
      }
    }
  }

  @Test
  void aBomReadsFirst() throws IOException {
    TextEncoding encoding = new TextEncoding(TextEncoding.forName("UTF-16LE").charset(), true);
    EncodedHexSource source = source("Ab", encoding);
    assertArrayEquals(
        new byte[] {(byte) 0xff, (byte) 0xfe, 'A', 0, 'b', 0}, read(source, 0, 6, null));
    assertArrayEquals(new byte[] {(byte) 0xfe, 'A'}, read(source, 1, 2, null));
  }

  @Test
  void anUnencodableCharReadsAsMarkedReplacementBytes() throws IOException {
    BitSet replaced = new BitSet();
    replaced.set(0, 4);
    EncodedHexSource latin = source("AB€C", TextEncoding.forName("ISO-8859-1"));
    assertArrayEquals(new byte[] {'A', 'B', '?', 'C'}, read(latin, 0, 4, replaced));
    assertEquals(bits(2), replaced);
    EncodedHexSource pair = source("A😀B", TextEncoding.forName("ISO-8859-1"));
    assertArrayEquals(new byte[] {'A', '?', '?', 'B'}, read(pair, 0, 4, replaced));
    assertEquals(bits(1, 2), replaced);
    EncodedHexSource sjis = source("A😀B", TextEncoding.forName("Shift_JIS"));
    assertEquals(3, sjis.size());
    assertArrayEquals(new byte[] {'A', '?', 'B'}, read(sjis, 0, 3, replaced));
    assertEquals(bits(1), replaced);
    EncodedHexSource lone = source("é\udc00x", TextEncoding.forName("UTF-8"));
    assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0xa9, '?', 'x'}, read(lone, 0, 4, replaced));
    assertEquals(bits(2), replaced);
    BitSet one = new BitSet();
    assertArrayEquals(new byte[] {'?'}, read(lone, 2, 1, one));
    assertEquals(bits(0), one);
  }

  private static BitSet bits(int... set) {
    BitSet bits = new BitSet();
    for (int b : set) {
      bits.set(b);
    }
    return bits;
  }
}
