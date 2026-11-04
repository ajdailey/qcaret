package io.github.ajdailey.qcaret.core.buffer;

import java.nio.CharBuffer;
import java.util.Objects;

/** The fields and read operations of {@link GapBufferStorage} and its snapshots. */
abstract class GapBufferText implements TextContent {
  char[] buf;
  int gs;
  int ge;
  int len;
  int[] lines;
  int lgs;
  int lge;

  GapBufferText(char[] buf, int gs, int ge, int len, int[] lines, int lgs, int lge) {
    this.buf = buf;
    this.gs = gs;
    this.ge = ge;
    this.len = len;
    this.lines = lines;
    this.lgs = lgs;
    this.lge = lge;
  }

  @Override
  public int length() {
    return len;
  }

  @Override
  public char charAt(int index) {
    Objects.checkIndex(index, len);
    return index < gs ? buf[index] : buf[index + ge - gs];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    Objects.checkFromToIndex(start, end, len);
    char[] out = new char[end - start];
    int a = Math.min(end, gs);
    if (start < a) {
      System.arraycopy(buf, start, out, 0, a - start);
    }
    int b = Math.max(start, gs);
    if (b < end) {
      System.arraycopy(buf, b + ge - gs, out, b - start, end - b);
    }
    return new String(out);
  }

  @Override
  public String toString() {
    return subSequence(0, len).toString();
  }

  private int breakCount() {
    return lgs + lines.length - lge;
  }

  /** Returns the start of line {@code j + 1}. */
  private int start(int j) {
    return j < lgs ? lines[j] : len - lines[j + lge - lgs];
  }

  @Override
  public int lineCount() {
    return breakCount() + 1;
  }

  @Override
  public int lineAt(int offset) {
    Objects.checkIndex(offset, len + 1);
    int lo = 0;
    int hi = breakCount();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (start(mid) <= offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  @Override
  public int offsetOfLine(int line) {
    Objects.checkIndex(line, lineCount());
    return line == 0 ? 0 : start(line - 1);
  }

  @Override
  public void readChunks(int start, int end, ChunkVisitor visitor) {
    Objects.checkFromToIndex(start, end, len);
    CharBuffer view = CharBuffer.wrap(buf).asReadOnlyBuffer();
    int a = Math.min(end, gs);
    if (start < a) {
      visitor.chunk(view, start, a);
    }
    int b = Math.max(start, gs);
    if (b < end) {
      visitor.chunk(view, b + ge - gs, end + ge - gs);
    }
  }
}
