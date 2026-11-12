package io.github.ajdailey.qcaret.app.theme;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the monospace font families by reading the {@code .ttf}, {@code .otf}, {@code .ttc} and
 * {@code .otc} files of the platform's font folders. SWT lists fonts only on the UI thread, so a
 * worker reads the files without the toolkit. A face is monospace when its {@code cmap} table maps
 * i, W and 0 and its {@code hmtx} table gives each the same nonzero advance. The {@code post}
 * table's {@code isFixedPitch} is not read: measured on Linux, Noto Color Emoji sets it without
 * having the letters and FreeMono leaves it clear. A file that does not parse is skipped.
 */
public final class FontFiles {
  /** Most files a scan opens. */
  static final int MAX_FILES = 20_000;

  /** Folder levels a scan descends. */
  static final int MAX_DEPTH = 8;

  /** Most faces read from one collection. */
  static final int MAX_FACES = 64;

  /** Largest {@code name} table read. */
  static final int MAX_NAME_TABLE_BYTES = 1 << 20;

  /** Largest {@code cmap} table read. */
  static final int MAX_CMAP_TABLE_BYTES = 1 << 24;

  /** Longest family name kept. */
  static final int MAX_FAMILY_CHARS = 128;

  private static final Logger LOG = LoggerFactory.getLogger(FontFiles.class);
  private static final int TTC = 0x74746366;
  private static final int TRUE_TYPE = 0x00010000;
  private static final int APPLE_TRUE_TYPE = 0x74727565;
  private static final int OPEN_TYPE = 0x4f54544f;
  private static final int CMAP = 0x636d6170;
  private static final int HHEA = 0x68686561;
  private static final int HMTX = 0x686d7478;
  private static final int NAME = 0x6e616d65;
  private static final int MAX_TABLES = 256;
  private static final int MAX_CMAP_SUBTABLES = 64;
  private static final int HMETRICS_AT = 34;
  private static final String PROBES = "iW0";
  private static final int NAME_FAMILY = 1;
  private static final int NAME_TYPOGRAPHIC_FAMILY = 16;
  private static final int PLATFORM_UNICODE = 0;
  private static final int PLATFORM_MAC = 1;
  private static final int PLATFORM_WINDOWS = 3;
  private static final int US_ENGLISH = 0x409;

  private FontFiles() {}

  /**
   * Scans this platform's font folders for monospace families.
   *
   * @return the family names, sorted, without duplicates
   */
  @WorkerThread
  public static List<String> installedFamilies() {
    return monospaceFamilies(
        OsTraits.current().fontDirectories(System.getenv(), System.getProperty("user.home", "")));
  }

  /**
   * Scans folders for monospace families.
   *
   * @param folders the folders; a missing one is skipped
   * @return the family names, sorted, without duplicates
   */
  @WorkerThread
  public static List<String> monospaceFamilies(List<Path> folders) {
    final TreeSet<String> families = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    final int[] opened = {0};
    for (Path folder : folders) {
      if (!Files.isDirectory(folder)) {
        continue;
      }
      try {
        Files.walkFileTree(
            folder,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            MAX_DEPTH,
            new SimpleFileVisitor<Path>() {
              @Override
              @WorkerThread
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && fontFile(file) && opened[0] < MAX_FILES) {
                  opened[0]++;
                  read(file, families);
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException e) {
        LOG.debug("Font folder skipped: {}", LogText.oneLine(folder));
      }
    }
    return new ArrayList<String>(families);
  }

  private static boolean fontFile(Path file) {
    String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
    return name.endsWith(".ttf")
        || name.endsWith(".otf")
        || name.endsWith(".ttc")
        || name.endsWith(".otc");
  }

  @WorkerThread
  private static void read(Path file, TreeSet<String> families) {
    try (SeekableByteChannel channel = Files.newByteChannel(file)) {
      readFaces(channel, families);
    } catch (IOException | IndexOutOfBoundsException e) {
      LOG.debug("Font file skipped: {}", LogText.oneLine(file));
    }
  }

  /** Adds the monospace families of an sfnt file or collection. */
  @WorkerThread
  static void readFaces(SeekableByteChannel channel, TreeSet<String> families) throws IOException {
    ByteBuffer head = bytes(channel, 0, 12);
    if (head.getInt(0) == TTC) {
      int faces = Math.max(0, Math.min(head.getInt(8), MAX_FACES));
      ByteBuffer offsets = bytes(channel, 12, 4 * faces);
      for (int i = 0; i < faces; i++) {
        readFace(channel, Integer.toUnsignedLong(offsets.getInt(4 * i)), families);
      }
    } else {
      readFace(channel, 0, families);
    }
  }

  private static void readFace(SeekableByteChannel channel, long at, TreeSet<String> families)
      throws IOException {
    ByteBuffer head = bytes(channel, at, 12);
    int version = head.getInt(0);
    int tables = head.getShort(4) & 0xffff;
    if ((version != TRUE_TYPE && version != APPLE_TRUE_TYPE && version != OPEN_TYPE)
        || tables > MAX_TABLES) {
      return;
    }
    ByteBuffer directory = bytes(channel, at + 12, 16 * tables);
    long[] offsets = new long[4];
    long[] lengths = new long[4];
    for (int i = 0; i < tables; i++) {
      int entry = 16 * i;
      int slot = slot(directory.getInt(entry));
      if (slot >= 0) {
        offsets[slot] = Integer.toUnsignedLong(directory.getInt(entry + 8));
        lengths[slot] = Integer.toUnsignedLong(directory.getInt(entry + 12));
      }
    }
    if (lengths[0] == 0
        || lengths[0] > MAX_CMAP_TABLE_BYTES
        || lengths[1] < HMETRICS_AT + 2
        || lengths[2] == 0
        || lengths[3] == 0
        || lengths[3] > MAX_NAME_TABLE_BYTES) {
      return;
    }
    int hmetrics = bytes(channel, offsets[1] + HMETRICS_AT, 2).getShort(0) & 0xffff;
    ByteBuffer cmap = bytes(channel, offsets[0], (int) lengths[0]);
    if (fixedAdvance(channel, cmap, offsets[2], lengths[2], hmetrics)) {
      ByteBuffer table = bytes(channel, offsets[3], (int) lengths[3]);
      String family = name(table, NAME_FAMILY);
      String typographic = name(table, NAME_TYPOGRAPHIC_FAMILY);
      // A family that differs from its typographic family is one weight of it: "Mono Light".
      if (family != null
          && family.length() <= MAX_FAMILY_CHARS
          && (typographic == null || typographic.equals(family))) {
        families.add(family);
      }
    }
  }

  /** Returns a table's index in the arrays of {@link #readFace}, or -1 for one the scan skips. */
  private static int slot(int tag) {
    return switch (tag) {
      case CMAP -> 0;
      case HHEA -> 1;
      case HMTX -> 2;
      case NAME -> 3;
      default -> -1;
    };
  }

  /** Reports whether every probe char has a glyph and they share one nonzero advance. */
  private static boolean fixedAdvance(
      SeekableByteChannel channel, ByteBuffer cmap, long hmtx, long hmtxLength, int hmetrics)
      throws IOException {
    if (hmetrics == 0 || hmtxLength < 4L * hmetrics) {
      return false;
    }
    int advance = 0;
    for (int i = 0; i < PROBES.length(); i++) {
      int glyph = glyph(cmap, PROBES.charAt(i));
      if (glyph <= 0) {
        return false;
      }
      // Glyphs past the last long metric share its advance.
      long at = hmtx + 4L * Math.min(glyph, hmetrics - 1);
      int width = bytes(channel, at, 2).getShort(0) & 0xffff;
      if (width == 0 || (advance != 0 && width != advance)) {
        return false;
      }
      advance = width;
    }
    return true;
  }

  /** Returns a char's glyph from the first Unicode subtable of format 4 or 12 that has it, or 0. */
  private static int glyph(ByteBuffer cmap, int c) {
    int count = Math.min(cmap.getShort(2) & 0xffff, MAX_CMAP_SUBTABLES);
    for (int i = 0; i < count; i++) {
      int record = 4 + 8 * i;
      int platform = cmap.getShort(record) & 0xffff;
      int encoding = cmap.getShort(record + 2) & 0xffff;
      if (platform == PLATFORM_UNICODE
          || (platform == PLATFORM_WINDOWS && (encoding == 1 || encoding == 10))) {
        int at = cmap.getInt(record + 4);
        int format = cmap.getShort(at) & 0xffff;
        int glyph = format == 4 ? glyph4(cmap, at, c) : format == 12 ? glyph12(cmap, at, c) : 0;
        if (glyph != 0) {
          return glyph;
        }
      }
    }
    return 0;
  }

  private static int glyph4(ByteBuffer cmap, int at, int c) {
    int segments = (cmap.getShort(at + 6) & 0xffff) / 2;
    int ends = at + 14;
    int starts = ends + 2 * segments + 2;
    int deltas = starts + 2 * segments;
    int ranges = deltas + 2 * segments;
    for (int i = 0; i < segments; i++) {
      int end = cmap.getShort(ends + 2 * i) & 0xffff;
      int start = cmap.getShort(starts + 2 * i) & 0xffff;
      if (c <= end) {
        if (c < start) {
          return 0;
        }
        int delta = cmap.getShort(deltas + 2 * i) & 0xffff;
        int range = cmap.getShort(ranges + 2 * i) & 0xffff;
        if (range == 0) {
          return (c + delta) & 0xffff;
        }
        int glyph = cmap.getShort(ranges + 2 * i + range + 2 * (c - start)) & 0xffff;
        return glyph == 0 ? 0 : (glyph + delta) & 0xffff;
      }
    }
    return 0;
  }

  private static int glyph12(ByteBuffer cmap, int at, int c) {
    int groups = cmap.getInt(at + 12);
    for (int i = 0; i < groups; i++) {
      int group = at + 16 + 12 * i;
      int start = cmap.getInt(group);
      int end = cmap.getInt(group + 4);
      if (c >= start && c <= end) {
        return cmap.getInt(group + 8) + c - start;
      }
    }
    return 0;
  }

  /** Returns a name, preferring Windows US English, then any Windows, Unicode and Mac record. */
  private static String name(ByteBuffer table, int id) {
    int count = table.getShort(2) & 0xffff;
    int strings = table.getShort(4) & 0xffff;
    String found = null;
    int best = 0;
    for (int i = 0; i < count; i++) {
      int record = 6 + 12 * i;
      int platform = table.getShort(record) & 0xffff;
      int rank = rank(platform, table.getShort(record + 4) & 0xffff);
      if ((table.getShort(record + 6) & 0xffff) == id && rank > best) {
        byte[] raw = new byte[table.getShort(record + 8) & 0xffff];
        table.get(strings + (table.getShort(record + 10) & 0xffff), raw);
        String text =
            new String(
                    raw,
                    platform == PLATFORM_MAC
                        ? StandardCharsets.ISO_8859_1
                        : StandardCharsets.UTF_16BE)
                .strip();
        if (!text.isEmpty()) {
          found = text;
          best = rank;
        }
      }
    }
    return found;
  }

  private static int rank(int platform, int language) {
    return switch (platform) {
      case PLATFORM_WINDOWS -> language == US_ENGLISH ? 4 : 3;
      case PLATFORM_UNICODE -> 2;
      case PLATFORM_MAC -> language == 0 ? 1 : 0;
      default -> 0;
    };
  }

  private static ByteBuffer bytes(SeekableByteChannel channel, long at, int length)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    channel.position(at);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        throw new IOException("Font file ends early");
      }
    }
    buffer.flip();
    return buffer;
  }
}
