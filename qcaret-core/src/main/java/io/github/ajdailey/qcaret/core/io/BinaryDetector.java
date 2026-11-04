package io.github.ajdailey.qcaret.core.io;

/**
 * Decides whether a file is binary from its first {@value #SAMPLE_BYTES} bytes: a NUL byte in a
 * file not encoded as UTF-16 or UTF-32, or more than {@value #CONTROL_PERCENT}% C0 control bytes
 * other than tab, LF, FF and CR.
 */
public final class BinaryDetector {
  /** Bytes examined. */
  public static final int SAMPLE_BYTES = 64 * 1024;

  /** Greatest share of control bytes, in percent, in a text file. */
  public static final int CONTROL_PERCENT = 10;

  private BinaryDetector() {}

  /**
   * Examines the head of a file.
   *
   * @param bytes the file content in {@code [0, length)}
   * @param length the file length
   * @param wideEncoding true when the file was detected as UTF-16 or UTF-32, whose text holds zero
   *     bytes; the control-byte share is then not checked either
   * @return true when the file is binary
   */
  public static boolean isBinary(byte[] bytes, int length, boolean wideEncoding) {
    if (wideEncoding) {
      return false;
    }
    int sample = Math.min(length, SAMPLE_BYTES);
    int controls = 0;
    for (int i = 0; i < sample; i++) {
      int b = bytes[i] & 0xff;
      if (b == 0) {
        return true;
      }
      if (b < 0x20 && b != '\t' && b != '\n' && b != '\f' && b != '\r') {
        controls++;
      }
    }
    return (long) controls * 100 > (long) sample * CONTROL_PERCENT;
  }
}
