package io.github.ajdailey.qcaret.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the scan over generated sfnt files: only the tables the scan reads, at real offsets. */
class FontFilesTest {
  @TempDir Path temp;

  /** A format 4 cmap: i is glyph 1, W glyph 2, 0 glyph 3, by delta. */
  private static byte[] cmap4() {
    int[][] segments = {{'0', 3 - '0'}, {'W', 2 - 'W'}, {'i', 1 - 'i'}, {0xffff, 1}};
    int count = segments.length;
    ByteBuffer table = ByteBuffer.allocate(12 + 16 + 8 * count);
    table.putShort((short) 0).putShort((short) 1);
    table.putShort((short) 3).putShort((short) 1).putInt(12);
    table.putShort((short) 4).putShort((short) (16 + 8 * count)).putShort((short) 0);
    table.putShort((short) (2 * count));
    table.position(table.position() + 6);
    for (int[] segment : segments) {
      table.putShort((short) segment[0]);
    }
    table.putShort((short) 0);
    for (int[] segment : segments) {
      table.putShort((short) segment[0]);
    }
    for (int[] segment : segments) {
      table.putShort((short) segment[1]);
    }
    return table.array();
  }

  /** A format 4 cmap that reaches its glyphs through idRangeOffset. */
  private static byte[] cmap4Ranged() {
    ByteBuffer table = ByteBuffer.allocate(12 + 16 + 8 * 2 + 2 * ('i' - '0' + 1));
    table.putShort((short) 0).putShort((short) 1);
    table.putShort((short) 0).putShort((short) 3).putInt(12);
    table.putShort((short) 4).putShort((short) 0).putShort((short) 0);
    table.putShort((short) 4);
    table.position(table.position() + 6);
    table.putShort((short) 'i').putShort((short) 0xffff).putShort((short) 0);
    table.putShort((short) '0').putShort((short) 0xffff);
    table.putShort((short) 0).putShort((short) 1);
    table.putShort((short) 4).putShort((short) 0);
    int glyphs = table.position();
    table.putShort(glyphs + 2 * ('0' - '0'), (short) 3);
    table.putShort(glyphs + 2 * ('W' - '0'), (short) 2);
    table.putShort(glyphs + 2 * ('i' - '0'), (short) 1);
    return table.array();
  }

  /** A format 12 cmap with the same glyphs as {@link #cmap4}. */
  private static byte[] cmap12() {
    ByteBuffer table = ByteBuffer.allocate(12 + 16 + 36);
    table.putShort((short) 0).putShort((short) 1);
    table.putShort((short) 3).putShort((short) 10).putInt(12);
    table.putShort((short) 12).putShort((short) 0).putInt(52).putInt(0).putInt(3);
    table.putInt('0').putInt('0').putInt(3);
    table.putInt('W').putInt('W').putInt(2);
    table.putInt('i').putInt('i').putInt(1);
    return table.array();
  }

  private static byte[] hhea(int hmetrics) {
    return ByteBuffer.allocate(36).putShort(34, (short) hmetrics).array();
  }

  /** Long metrics for glyphs 0 onward; the first entry is .notdef. */
  private static byte[] hmtx(int... advances) {
    ByteBuffer table = ByteBuffer.allocate(4 * advances.length);
    for (int advance : advances) {
      table.putShort((short) advance).putShort((short) 0);
    }
    return table.array();
  }

  /** One name table; each record is {platform, language, id, text}. */
  private static byte[] names(Object[]... records) {
    ByteArrayOutputStream strings = new ByteArrayOutputStream();
    ByteBuffer table = ByteBuffer.allocate(6 + 12 * records.length + 1024);
    table.putShort((short) 0).putShort((short) records.length);
    table.putShort((short) (6 + 12 * records.length));
    for (Object[] record : records) {
      int platform = (Integer) record[0];
      byte[] text =
          ((String) record[3])
              .getBytes(platform == 1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
      table.putShort((short) platform).putShort((short) (platform == 3 ? 1 : 0));
      table.putShort(((Integer) record[1]).shortValue());
      table.putShort(((Integer) record[2]).shortValue());
      table.putShort((short) text.length).putShort((short) strings.size());
      strings.writeBytes(text);
    }
    table.put(strings.toByteArray());
    return Arrays.copyOf(table.array(), table.position());
  }

  private static byte[] family(String name) {
    return names(new Object[] {3, 0x409, 1, name});
  }

  /** Builds a face placed at an offset in its file. */
  private static byte[] face(int version, int base, byte[] cmap, byte[] hmtx, byte[] name) {
    byte[] hhea = hhea(hmtx.length / 4);
    Object[][] tables = {{"cmap", cmap}, {"hhea", hhea}, {"hmtx", hmtx}, {"name", name}};
    int data = 12 + 16 * tables.length;
    int size = data;
    for (Object[] table : tables) {
      size += ((byte[]) table[1]).length;
    }
    ByteBuffer out = ByteBuffer.allocate(size);
    out.putInt(version).putShort((short) tables.length);
    out.position(12);
    int at = base + data;
    for (Object[] table : tables) {
      byte[] bytes = (byte[]) table[1];
      out.put(((String) table[0]).getBytes(StandardCharsets.ISO_8859_1));
      out.putInt(0).putInt(at).putInt(bytes.length);
      at += bytes.length;
    }
    for (Object[] table : tables) {
      out.put((byte[]) table[1]);
    }
    return out.array();
  }

  private static byte[] mono(byte[] name) {
    return face(0x00010000, 0, cmap4(), hmtx(600, 600, 600, 600), name);
  }

  private List<String> scan() {
    return FontFiles.monospaceFamilies(List.of(temp));
  }

  private void write(String file, byte[] bytes) throws Exception {
    Files.write(temp.resolve(file), bytes);
  }

  @Test
  void equalAdvancesAreListedAndUnequalOnesAreNot() throws Exception {
    write("a.ttf", mono(family("Mock Mono")));
    write("b.ttf", face(0x00010000, 0, cmap4(), hmtx(500, 300, 900, 600), family("Mock Sans")));
    assertEquals(List.of("Mock Mono"), scan());
  }

  @Test
  void eachCmapFormatIsRead() throws Exception {
    byte[] advances = hmtx(600, 600, 600, 600);
    write("a.ttf", face(0x00010000, 0, cmap12(), advances, family("Mock Twelve")));
    write("b.ttf", face(0x00010000, 0, cmap4Ranged(), advances, family("Mock Ranged")));
    assertEquals(List.of("Mock Ranged", "Mock Twelve"), scan());
  }

  @Test
  void openTypeAndAppleVersionsAreRead() throws Exception {
    byte[] advances = hmtx(600, 600, 600, 600);
    write("a.otf", face(0x4f54544f, 0, cmap4(), advances, family("Mock Otf")));
    write("b.ttf", face(0x74727565, 0, cmap4(), advances, family("Mock Apple")));
    assertEquals(List.of("Mock Apple", "Mock Otf"), scan());
  }

  @Test
  void aFaceWithoutTheLettersIsNotListed() throws Exception {
    ByteBuffer empty = ByteBuffer.allocate(4).putShort(2, (short) 0);
    write("a.ttf", face(0x00010000, 0, empty.array(), hmtx(600, 600), family("Mock Emoji")));
    assertEquals(List.of(), scan());
  }

  @Test
  void glyphsPastTheLastMetricShareItsAdvance() throws Exception {
    write("a.ttf", face(0x00010000, 0, cmap4(), hmtx(500, 600), family("Mock Short")));
    assertEquals(List.of("Mock Short"), scan());
  }

  @Test
  void aZeroAdvanceIsNotListed() throws Exception {
    write("a.ttf", face(0x00010000, 0, cmap4(), hmtx(0, 0, 0, 0), family("Mock Zero")));
    assertEquals(List.of(), scan());
  }

  @Test
  void everyFaceOfACollectionIsRead() throws Exception {
    int header = 12 + 8;
    byte[] advances = hmtx(600, 600, 600, 600);
    byte[] first = face(0x00010000, header, cmap4(), advances, family("Mock One"));
    byte[] second = face(0x00010000, header + first.length, cmap4(), advances, family("Mock Two"));
    ByteBuffer file = ByteBuffer.allocate(header + first.length + second.length);
    file.putInt(0x74746366).putInt(0x00010000).putInt(2);
    file.putInt(header).putInt(header + first.length);
    file.put(first).put(second);
    write("a.ttc", file.array());
    assertEquals(List.of("Mock One", "Mock Two"), scan());
  }

  @Test
  void aWeightOfATypographicFamilyIsLeftOut() throws Exception {
    write(
        "regular.ttf",
        mono(names(new Object[] {3, 0x409, 1, "Mock"}, new Object[] {3, 0x409, 16, "Mock"})));
    write(
        "light.ttf",
        mono(names(new Object[] {3, 0x409, 1, "Mock Light"}, new Object[] {3, 0x409, 16, "Mock"})));
    assertEquals(List.of("Mock"), scan());
  }

  @Test
  void windowsUsEnglishIsPreferredOverOtherNames() throws Exception {
    write(
        "a.ttf",
        mono(
            names(
                new Object[] {1, 0, 1, "Mock Mac"},
                new Object[] {3, 0x407, 1, "Mock Deutsch"},
                new Object[] {3, 0x409, 1, "Mock English"})));
    assertEquals(List.of("Mock English"), scan());
  }

  @Test
  void aMacNameIsUsedWhenItIsTheOnlyOne() throws Exception {
    write("a.ttf", mono(names(new Object[] {1, 0, 1, "Mock Mac"})));
    assertEquals(List.of("Mock Mac"), scan());
  }

  @Test
  void duplicatesCollapseAcrossFilesAndFolders() throws Exception {
    Files.createDirectories(temp.resolve("sub"));
    write("a.ttf", mono(family("Mock")));
    write("sub/a.ttf", mono(family("Mock")));
    assertEquals(List.of("Mock"), scan());
  }

  @Test
  void aBrokenFileIsSkipped() throws Exception {
    byte[] good = mono(family("Mock"));
    write("short.ttf", Arrays.copyOf(good, 40));
    write("empty.ttf", new byte[0]);
    write("text.ttf", "not a font at all".getBytes(StandardCharsets.UTF_8));
    write("badtables.ttf", ByteBuffer.allocate(12).putInt(0x00010000).putShort((short) -1).array());
    byte[] badOffset = good.clone();
    ByteBuffer.wrap(badOffset).putInt(12 + 8, Integer.MAX_VALUE);
    write("badoffset.ttf", badOffset);
    byte[] badName = mono(family("Mock Bad"));
    int nameTable = badName.length - family("Mock Bad").length;
    ByteBuffer.wrap(badName).putShort(nameTable + 2, Short.MAX_VALUE);
    write("badname.ttf", badName);
    byte[] badCmap = cmap4();
    ByteBuffer.wrap(badCmap).putInt(8, Integer.MAX_VALUE);
    write("badcmap.ttf", face(0x00010000, 0, badCmap, hmtx(600, 600), family("Mock Cmap")));
    write("good.ttf", good);
    assertEquals(List.of("Mock"), scan());
  }

  @Test
  void otherFilesAreNotOpened() throws Exception {
    write("a.txt", mono(family("Mock")));
    assertEquals(List.of(), scan());
  }

  @Test
  void aMissingFolderIsSkipped() {
    assertEquals(List.of(), FontFiles.monospaceFamilies(List.of(temp.resolve("absent"))));
  }

  @Test
  void eachPlatformHasItsFolders() {
    assertEquals(
        List.of(Path.of("C:/Windows", "Fonts"), Path.of("C:/Local", "Microsoft/Windows/Fonts")),
        FontFiles.directories(
            "Windows 11", Map.of("WINDIR", "C:/Windows", "LOCALAPPDATA", "C:/Local"), "C:/home"));
    assertEquals(
        List.of(
            Path.of("/System/Library/Fonts"),
            Path.of("/Library/Fonts"),
            Path.of("/home/mock/Library/Fonts")),
        FontFiles.directories("Mac OS X", Map.<String, String>of(), "/home/mock"));
    assertEquals(
        List.of(
            Path.of("/home/mock/.fonts"),
            Path.of("/home/mock/.local/share/fonts"),
            Path.of("/usr/local/share/fonts"),
            Path.of("/usr/share/fonts")),
        FontFiles.directories("Linux", Map.<String, String>of(), "/home/mock"));
    List<Path> xdg =
        FontFiles.directories(
            "Linux", Map.of("XDG_DATA_HOME", "/d", "XDG_DATA_DIRS", "/a:/b"), "/home/mock");
    assertTrue(xdg.containsAll(List.of(Path.of("/d/fonts"), Path.of("/a/fonts"))));
    assertTrue(xdg.contains(Path.of("/b/fonts")));
  }
}
