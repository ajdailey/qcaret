package io.github.ajdailey.qcaret.core.text;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * windows-1252 as the WHATWG Encoding Standard defines it: the JDK mapping, except that 0x81, 0x8D,
 * 0x8F, 0x90 and 0x9D, which the JDK decodes as U+FFFD, decode as the C1 code point of the same
 * value. Every byte decodes to one char and the encoder maps each such char back to its byte. Its
 * name and aliases are the JDK charset's, so it equals that charset; {@link Charsets} and {@link
 * TextEncoding} substitute it for that charset.
 */
public final class Windows1252 extends Charset {
  /** The instance. */
  public static final Windows1252 INSTANCE = new Windows1252();

  private static final String NAME = "windows-1252";

  /** The char each byte decodes to, indexed by the unsigned byte. */
  private static final char[] CHARS = new char[256];

  /** The byte each char up to {@link #MAX_CHAR} encodes to, plus one; 0 for none. */
  private static final short[] BYTES;

  /** The highest char the charset holds, the trade mark sign. */
  private static final char MAX_CHAR = '™';

  static {
    byte[] all = new byte[256];
    for (int b = 0; b < 256; b++) {
      all[b] = (byte) b;
    }
    CharsetDecoder jdk =
        Charsets.jdk(NAME)
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    CharBuffer decoded;
    try {
      decoded = jdk.decode(ByteBuffer.wrap(all));
    } catch (CharacterCodingException e) {
      throw new IllegalStateException(e);
    }
    BYTES = new short[MAX_CHAR + 1];
    for (int b = 0; b < 256; b++) {
      char c = decoded.get(b);
      if (c == '�') {
        c = (char) b;
      }
      CHARS[b] = c;
      BYTES[c] = (short) (b + 1);
    }
  }

  private Windows1252() {
    super(NAME, Charsets.jdk(NAME).aliases().toArray(new String[0]));
  }

  /**
   * Returns this charset for any charset named {@code windows-1252}, else the charset given.
   *
   * @param charset a charset
   * @return the charset to decode and encode with
   */
  public static Charset substitute(Charset charset) {
    return NAME.equals(charset.name()) ? INSTANCE : charset;
  }

  @Override
  public boolean contains(Charset other) {
    return NAME.equals(other.name()) || StandardCharsets.US_ASCII.equals(other);
  }

  @Override
  public CharsetDecoder newDecoder() {
    return new Decoder(this);
  }

  @Override
  public CharsetEncoder newEncoder() {
    return new Encoder(this);
  }

  private static final class Decoder extends CharsetDecoder {
    Decoder(Charset charset) {
      super(charset, 1.0f, 1.0f);
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
      while (in.hasRemaining()) {
        if (!out.hasRemaining()) {
          return CoderResult.OVERFLOW;
        }
        out.put(CHARS[in.get() & 0xff]);
      }
      return CoderResult.UNDERFLOW;
    }
  }

  private static final class Encoder extends CharsetEncoder {
    Encoder(Charset charset) {
      super(charset, 1.0f, 1.0f);
    }

    @Override
    public boolean canEncode(char c) {
      return c <= MAX_CHAR && BYTES[c] != 0;
    }

    @Override
    protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
      while (in.hasRemaining()) {
        char c = in.get(in.position());
        if (!canEncode(c)) {
          return CoderResult.unmappableForLength(1);
        }
        if (!out.hasRemaining()) {
          return CoderResult.OVERFLOW;
        }
        in.position(in.position() + 1);
        out.put((byte) (BYTES[c] - 1));
      }
      return CoderResult.UNDERFLOW;
    }
  }
}
