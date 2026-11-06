package io.github.ajdailey.qcaret.app.ipc;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The handoff exchange's constants and its HMAC-SHA256 proofs. Nonces, proofs, paths and encoding
 * names travel as unpadded base64url, text as its UTF-8 bytes; the token never crosses the socket.
 *
 * <pre>
 * C: QCARET 1 HELLO nonceC
 * S: QCARET 1 PROOF nonceS HMAC(token, "server" nonceC nonceS)
 * C: AUTH HMAC(token, "client" nonceS nonceC)
 * C: WINDOW 0|1 x y width height          (1 = new window; - = not given)
 * C: OPEN path line column readOnly encoding   (repeated; 0 = none, encoding - = none)
 * C: DIFF left right                      (at most once)
 * C: END
 * S: OK | ERR
 * </pre>
 */
final class Protocol {
  /** Longest line in bytes, without its LF. */
  static final int MAX_LINE = 16 * 1024;

  /** Most bytes a server reads from one connection. */
  static final long MAX_REQUEST = 1L << 20;

  /** Most {@code OPEN} lines in one request. */
  static final int MAX_OPEN = 256;

  /** Bytes in a nonce and in the token. */
  static final int SECRET_BYTES = 32;

  static final String HELLO = "QCARET 1 HELLO ";
  static final String PROOF = "QCARET 1 PROOF ";
  static final String AUTH = "AUTH ";
  static final String WINDOW = "WINDOW";
  static final String OPEN = "OPEN ";
  static final String DIFF = "DIFF ";
  static final String END = "END";
  static final String OK = "OK";
  static final String ERR = "ERR";

  /** A field that was not given. */
  static final String NONE = "-";

  private static final SecureRandom RANDOM = new SecureRandom();

  private Protocol() {}

  /**
   * Returns fresh random bytes for a nonce or the token.
   *
   * @return {@link #SECRET_BYTES} bytes
   */
  static byte[] secret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Decodes base64url.
   *
   * @return the bytes; empty when the text is not base64url
   */
  static byte[] decode(String text) {
    try {
      return Base64.getUrlDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  /**
   * Computes a proof.
   *
   * @param token the shared secret
   * @param label {@code server} or {@code client}
   * @param first the first nonce
   * @param second the second nonce
   * @return HMAC-SHA256 over the label and the two nonces
   */
  static byte[] proof(byte[] token, String label, byte[] first, byte[] second) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token, "HmacSHA256"));
      mac.update(label.getBytes(StandardCharsets.US_ASCII));
      mac.update(first);
      mac.update(second);
      return mac.doFinal();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  /**
   * Compares a received proof with the expected one in constant time.
   *
   * @param expected the computed proof
   * @param received the decoded proof
   * @return true when they match
   */
  static boolean matches(byte[] expected, byte[] received) {
    return MessageDigest.isEqual(expected, received);
  }
}
