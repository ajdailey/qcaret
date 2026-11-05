package io.github.ajdailey.qcaret.core.io;

/**
 * What a document last matched on disk: the file's stamp and the SHA-256 of its bytes. External
 * change detection compares the stamp first and the digest only when the stamp differs or is too
 * recent to trust ({@link #racy}).
 *
 * @param stamp the stamp read before the bytes
 * @param digest the SHA-256 of the bytes, lower-case hex
 * @param recordedMillis wall-clock time the baseline was taken
 */
public record FileBaseline(FileStamp stamp, String digest, long recordedMillis) {
  /**
   * Modification times closer than this to the time a baseline was taken may hide a later write
   * with the same size: a file system with a coarse clock records both writes with one time.
   */
  public static final long RACY_MILLIS = 2000;

  /**
   * Reports whether the stamp alone cannot show that the file is unchanged: it was modified within
   * {@link #RACY_MILLIS} of the time the baseline was taken.
   *
   * @return true when a check must compare the digest
   */
  public boolean racy() {
    return stamp.modified().toMillis() + RACY_MILLIS > recordedMillis;
  }
}
