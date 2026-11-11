package io.github.ajdailey.qcaret.core.io;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A digest the Tools menu computes and verifies. MD5 and SHA-1 detect accidental change only; a
 * collision can be made for either.
 */
public enum ChecksumAlgorithm {
  /** MD5, 32 hex digits. */
  MD5("MD5", 32, false),
  /** SHA-1, 40 hex digits. */
  SHA_1("SHA-1", 40, false),
  /** SHA-256, 64 hex digits. */
  SHA_256("SHA-256", 64, true),
  /** SHA-512, 128 hex digits. */
  SHA_512("SHA-512", 128, true);

  private final String javaName;
  private final int hexLength;
  private final boolean collisionResistant;

  ChecksumAlgorithm(String javaName, int hexLength, boolean collisionResistant) {
    this.javaName = javaName;
    this.hexLength = hexLength;
    this.collisionResistant = collisionResistant;
  }

  /**
   * Returns the name {@link MessageDigest#getInstance(String)} takes, also the display name.
   *
   * @return for example {@code SHA-256}
   */
  public String javaName() {
    return javaName;
  }

  /**
   * Returns the length of the digest in hex digits.
   *
   * @return 32, 40, 64 or 128
   */
  public int hexLength() {
    return hexLength;
  }

  /**
   * Reports whether no practical collision attack is known.
   *
   * @return false for MD5 and SHA-1
   */
  public boolean collisionResistant() {
    return collisionResistant;
  }

  /**
   * Creates a digest.
   *
   * @return a new digest
   * @throws NoSuchAlgorithmException when no provider offers the algorithm, as under a FIPS-only
   *     provider set for MD5
   */
  public MessageDigest newDigest() throws NoSuchAlgorithmException {
    return MessageDigest.getInstance(javaName);
  }

  /**
   * Returns the algorithm whose digest has a hex length.
   *
   * @param length a count of hex digits
   * @return the algorithm, or null for any other length
   */
  public static ChecksumAlgorithm forHexLength(int length) {
    for (ChecksumAlgorithm algorithm : values()) {
      if (algorithm.hexLength == length) {
        return algorithm;
      }
    }
    return null;
  }
}
