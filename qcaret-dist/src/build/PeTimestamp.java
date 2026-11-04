import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.Instant;

/**
 * Sets the COFF header time stamp of a PE file and rewrites its optional-header checksum.
 *
 * <p>Usage: {@code java PeTimestamp.java <file> <ISO-8601 instant>}. The result matches what GNU
 * ld writes with {@code SOURCE_DATE_EPOCH} set to that instant.
 */
public final class PeTimestamp {

  private PeTimestamp() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: PeTimestamp <file> <ISO-8601 instant>");
    }
    long seconds = Instant.parse(args[1]).getEpochSecond();
    try (RandomAccessFile file = new RandomAccessFile(args[0], "rw")) {
      if (file.length() > Integer.MAX_VALUE) {
        throw new IOException(args[0] + " is too large for a PE image");
      }
      byte[] image = new byte[(int) file.length()];
      file.readFully(image);
      int pe = readInt(image, 0x3c);
      if (pe < 0
          || pe + 0x5c > image.length
          || image[pe] != 'P'
          || image[pe + 1] != 'E'
          || image[pe + 2] != 0
          || image[pe + 3] != 0) {
        throw new IOException(args[0] + " is not a PE image");
      }
      int checksumOffset = pe + 0x58;
      writeInt(image, pe + 8, (int) seconds);
      writeInt(image, checksumOffset, 0);
      writeInt(image, checksumOffset, checksum(image, checksumOffset));
      file.seek(0);
      file.write(image);
    }
  }

  /** The PE checksum: a folded 16-bit word sum of the image plus its length. */
  private static int checksum(byte[] image, int checksumOffset) {
    long sum = 0;
    for (int i = 0; i < image.length; i += 2) {
      if (i == checksumOffset || i == checksumOffset + 2) {
        continue;
      }
      int high = i + 1 < image.length ? image[i + 1] & 0xff : 0;
      sum += (image[i] & 0xff) | (high << 8);
      sum = (sum & 0xffff) + (sum >>> 16);
    }
    sum = (sum & 0xffff) + (sum >>> 16);
    return (int) (sum + image.length);
  }

  private static int readInt(byte[] b, int offset) {
    return (b[offset] & 0xff)
        | (b[offset + 1] & 0xff) << 8
        | (b[offset + 2] & 0xff) << 16
        | (b[offset + 3] & 0xff) << 24;
  }

  private static void writeInt(byte[] b, int offset, int value) {
    b[offset] = (byte) value;
    b[offset + 1] = (byte) (value >>> 8);
    b[offset + 2] = (byte) (value >>> 16);
    b[offset + 3] = (byte) (value >>> 24);
  }
}
