package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ByteOffsetsTest {
  private static final int BLOCK = ByteOffsets.BLOCK_CHARS;
  private static final int LENGTH = 2 * BLOCK + 5000;

  /** Every menu charset, plus a BOM where the charset has one. */
  static List<TextEncoding> encodings() {
    List<TextEncoding> all = new ArrayList<TextEncoding>();
    for (String name : TextEncoding.MENU) {
      TextEncoding plain = TextEncoding.forName(name);
      all.add(plain);
      if (new TextEncoding(plain.charset(), false).unitWidth() > 1 || name.equals("UTF-8")) {
        all.add(new TextEncoding(plain.charset(), true));
      }
    }
    return all;
  }

  private static TextSnapshot snapshot(String text) {
    return RopeStorage.of(text).snapshot();
  }

  @Test
  void arithmeticCharsetsHoldNoIndex() {
    List<String> arithmetic = new ArrayList<String>();
    for (String name : TextEncoding.MENU) {
      if (!ByteOffsets.indexed(TextEncoding.forName(name))) {
        arithmetic.add(name);
      }
    }
    assertEquals(
        List.of(
            "UTF-16LE",
            "UTF-16BE",
            "ISO-8859-1",
            "ISO-8859-15",
            "windows-1252",
            "US-ASCII",
            "windows-1250",
            "windows-1251",
            "KOI8-R"),
        arithmetic);
  }

  @Test
  void aPairAcrossABlockBoundaryStaysInTheEarlierBlock() {
    String text = HexFixtures.text(LENGTH, 7);
    assertTrue(Character.isHighSurrogate(text.charAt(BLOCK - 1)));
    ByteOffsets offsets =
        ByteOffsets.build(snapshot(text), TextEncoding.forName("UTF-8"), new CancellationToken());
    assertEquals(3, offsets.blockCount());
    assertEquals(BLOCK + 1, offsets.blockStart(1));
    assertEquals(2 * BLOCK + 1, offsets.blockStart(2));
    assertEquals(0, offsets.blockOfChar(BLOCK));
    assertEquals(1, offsets.blockOfChar(BLOCK + 1));
  }

  @Test
  void byteOffsetsMatchAWholeEncodeInEveryCharset() {
    String text = HexFixtures.text(LENGTH, 11);
    TreeSet<Integer> samples = new TreeSet<Integer>();
    for (int c : new int[] {0, 1, BLOCK - 1, BLOCK, BLOCK + 1, 2 * BLOCK, LENGTH - 1, LENGTH}) {
      samples.add(c);
    }
    SplittableRandom random = new SplittableRandom(11);
    while (samples.size() < 30) {
      samples.add(random.nextInt(LENGTH + 1));
    }
    TextSnapshot snapshot = snapshot(text);
    for (TextEncoding encoding : encodings()) {
      String name = encoding.charset().name() + (encoding.bom() ? " BOM" : "");
      ByteOffsets offsets = ByteOffsets.build(snapshot, encoding, new CancellationToken());
      byte[] whole = HexFixtures.wholeEncode(text, encoding);
      assertEquals(whole.length, offsets.size(), name);
      for (int c : samples) {
        int start = SliceEncoder.splitsPair(text, c) ? c - 1 : c;
        long expected = HexFixtures.wholeEncode(text.substring(0, start), encoding).length;
        assertEquals(expected, offsets.byteOffset(c), name + " char " + c);
      }
    }
  }

  @Test
  void eachByteMapsToTheCharHoldingIt() {
    String text = HexFixtures.text(LENGTH, 13);
    TextSnapshot snapshot = snapshot(text);
    for (TextEncoding encoding : encodings()) {
      String name = encoding.charset().name() + (encoding.bom() ? " BOM" : "");
      ByteOffsets offsets = ByteOffsets.build(snapshot, encoding, new CancellationToken());
      TreeSet<Long> bytes = new TreeSet<Long>();
      for (int block = 0; block < offsets.blockCount(); block++) {
        long start = offsets.bomLength() + offsets.blockByteStart(block);
        for (long b = Math.max(0, start - 5); b < start + 5; b++) {
          bytes.add(b);
        }
      }
      SplittableRandom random = new SplittableRandom(13);
      for (int i = 0; i < 200; i++) {
        bytes.add(random.nextLong(offsets.size()));
      }
      bytes.add(offsets.size());
      for (long b : bytes) {
        int c = offsets.charOffset(b);
        if (b >= offsets.size()) {
          assertEquals(LENGTH, c, name);
          continue;
        }
        assertFalse(SliceEncoder.splitsPair(text, c), name + " byte " + b);
        int next = Character.isHighSurrogate(text.charAt(c)) && c + 1 < LENGTH ? c + 2 : c + 1;
        if (next == c + 2 && !Character.isLowSurrogate(text.charAt(c + 1))) {
          next = c + 1;
        }
        long first = c == 0 ? 0 : offsets.byteOffset(c);
        assertTrue(first <= b && b < offsets.byteOffset(next), name + " byte " + b + " char " + c);
      }
    }
  }

  @Test
  void anUpdateEqualsAFullBuild() {
    for (String name : new String[] {"UTF-8", "GB18030", "UTF-32LE", "Shift_JIS"}) {
      TextEncoding encoding = TextEncoding.forName(name);
      SplittableRandom random = new SplittableRandom(name.hashCode());
      RopeStorage storage = RopeStorage.of(HexFixtures.text(LENGTH, 17));
      ByteOffsets offsets =
          ByteOffsets.build(storage.snapshot(), encoding, new CancellationToken());
      for (int step = 0; step < 40; step++) {
        int length = storage.length();
        int at;
        int remove;
        String insert;
        if (step % 4 == 0) {
          at = BLOCK;
          remove = 1;
          insert = step % 8 == 0 ? "\udc00" : "😀z";
        } else {
          at = random.nextInt(length);
          remove = Math.min(length - at, random.nextInt(4000));
          insert = HexFixtures.text(1 + random.nextInt(4000), step);
        }
        storage.replace(at, remove, insert);
        TextSnapshot next = storage.snapshot();
        ByteOffsets updated = offsets.update(next, at, new CancellationToken());
        ByteOffsets full = ByteOffsets.build(next, encoding, new CancellationToken());
        assertEquals(full.blockCount(), updated.blockCount(), name + " step " + step);
        for (int block = 0; block < full.blockCount(); block++) {
          assertEquals(
              full.blockByteStart(block), updated.blockByteStart(block), name + " step " + step);
        }
        assertEquals(full.size(), updated.size(), name + " step " + step);
        offsets = updated;
      }
    }
  }

  @Test
  void aCanceledBuildThrowsOnlyWhenIndexed() {
    final TextSnapshot text = snapshot(HexFixtures.text(LENGTH, 19));
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            ByteOffsets.build(text, TextEncoding.forName("UTF-8"), token);
          }
        });
    ByteOffsets latin = ByteOffsets.build(text, TextEncoding.forName("ISO-8859-1"), token);
    assertEquals(LENGTH, latin.size());
  }

  @Test
  void anEmptyTextIsItsBom() {
    TextEncoding encoding = new TextEncoding(TextEncoding.forName("UTF-8").charset(), true);
    ByteOffsets offsets = ByteOffsets.build(snapshot(""), encoding, new CancellationToken());
    assertEquals(3, offsets.size());
    assertEquals(0, offsets.blockCount());
    assertEquals(3, offsets.byteOffset(0));
    assertEquals(0, offsets.charOffset(0));
    assertEquals(0, offsets.charOffset(2));
    assertEquals(0, offsets.charOffset(3));
  }
}
