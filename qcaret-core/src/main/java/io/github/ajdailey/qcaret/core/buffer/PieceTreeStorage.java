package io.github.ajdailey.qcaret.core.buffer;

import java.nio.CharBuffer;
import java.util.Objects;

/**
 * Persistent piece tree. The decoded text is held in immutable {@code String} chunks of at most 64
 * Ki chars, each with the offsets of its line breaks; inserted text goes to append-only add chunks,
 * or to new immutable chunks when longer than {@value #ADD_PIECE_MAX} chars. An AVL tree of pieces
 * (chunk, start, length) aggregates length and line breaks per node, and an edit copies only the
 * path it changes. {@link #snapshot()} is O(1): it returns the current root.
 */
public final class PieceTreeStorage implements TextStorage {
  static final int CHUNK_CHARS = 64 * 1024;

  /** Longest piece in an add chunk, which bounds the line-break scan of one piece. */
  static final int ADD_PIECE_MAX = 4096;

  private Text current;
  private AddChunk add = new AddChunk();

  private PieceTreeStorage(Node root) {
    current = new Text(root);
  }

  /**
   * Builds a storage holding a copy of {@code text}.
   *
   * @param text the initial text
   * @return the storage
   */
  public static PieceTreeStorage of(CharSequence text) {
    return new PieceTreeStorage(stringPieces(text));
  }

  @Override
  public void replace(int offset, int removeLength, CharSequence text) {
    Objects.checkFromIndexSize(offset, removeLength, length());
    Node[] out = new Node[2];
    split(current.root, offset, out);
    Node left = out[0];
    split(out[1], removeLength, out);
    Node right = out[1];
    if (text.length() > 0) {
      left = insert(left, text);
    }
    current = new Text(join2(left, right));
  }

  private Node insert(Node left, CharSequence text) {
    int n = text.length();
    if (n > ADD_PIECE_MAX) {
      return join2(left, stringPieces(text));
    }
    if (add.used + n > CHUNK_CHARS) {
      add = new AddChunk();
    }
    int at = add.append(text);
    if (left != null) {
      Node last = last(left);
      if (last.chunk == add && last.start + last.length == at && last.length + n <= ADD_PIECE_MAX) {
        int breaks =
            last.breaks + add.count(at, at + n) - (last.lastCr && add.data[at] == '\n' ? 1 : 0);
        Node grown =
            new Node(
                null,
                add,
                last.start,
                last.length + n,
                breaks,
                last.firstLf,
                add.data[at + n - 1] == '\r',
                null);
        return join(removeLast(left), grown, null);
      }
    }
    return join(left, Node.piece(add, at, n), null);
  }

  @Override
  public TextSnapshot snapshot() {
    return current;
  }

  @Override
  public int length() {
    return current.length();
  }

  @Override
  public char charAt(int index) {
    return current.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return current.subSequence(start, end);
  }

  @Override
  public String toString() {
    return current.toString();
  }

  @Override
  public int lineCount() {
    return current.lineCount();
  }

  @Override
  public int lineAt(int offset) {
    return current.lineAt(offset);
  }

  @Override
  public int offsetOfLine(int line) {
    return current.offsetOfLine(line);
  }

  @Override
  public void readChunks(int start, int end, ChunkVisitor visitor) {
    current.readChunks(start, end, visitor);
  }

  private static Node stringPieces(CharSequence text) {
    int n = text.length();
    int count = (n + CHUNK_CHARS - 1) / CHUNK_CHARS;
    Node[] pieces = new Node[count];
    for (int i = 0; i < count; i++) {
      int s = i * CHUNK_CHARS;
      int e = Math.min(n, s + CHUNK_CHARS);
      StringChunk chunk = new StringChunk(text.subSequence(s, e).toString());
      pieces[i] = Node.piece(chunk, 0, e - s);
    }
    return build(pieces, 0, count);
  }

  private static Node build(Node[] pieces, int from, int to) {
    if (from >= to) {
      return null;
    }
    int mid = (from + to) >>> 1;
    return pieces[mid].with(build(pieces, from, mid), build(pieces, mid + 1, to));
  }

  private static int height(Node n) {
    return n == null ? 0 : n.height;
  }

  private static int size(Node n) {
    return n == null ? 0 : n.size;
  }

  private static Node balance(Node l, Node m, Node r) {
    int hl = height(l);
    int hr = height(r);
    if (hl > hr + 1) {
      if (height(l.left) >= height(l.right)) {
        return l.with(l.left, m.with(l.right, r));
      }
      Node lr = l.right;
      return lr.with(l.with(l.left, lr.left), m.with(lr.right, r));
    }
    if (hr > hl + 1) {
      if (height(r.right) >= height(r.left)) {
        return r.with(m.with(l, r.left), r.right);
      }
      Node rl = r.left;
      return rl.with(m.with(l, rl.left), r.with(rl.right, r.right));
    }
    return m.with(l, r);
  }

  /** Joins two trees around the piece of {@code m}; any height difference is allowed. */
  private static Node join(Node l, Node m, Node r) {
    int hl = height(l);
    int hr = height(r);
    if (hl > hr + 1) {
      return balance(l.left, l, join(l.right, m, r));
    }
    if (hr > hl + 1) {
      return balance(join(l, m, r.left), r, r.right);
    }
    return m.with(l, r);
  }

  private static Node join2(Node l, Node r) {
    if (l == null) {
      return r;
    }
    if (r == null) {
      return l;
    }
    return join(removeLast(l), last(l), r);
  }

  private static Node last(Node n) {
    Node c = n;
    while (c.right != null) {
      c = c.right;
    }
    return c;
  }

  private static Node removeLast(Node n) {
    if (n.right == null) {
      return n.left;
    }
    return balance(n.left, n, removeLast(n.right));
  }

  /** Splits {@code n} into {@code out[0]}, its first {@code offset} chars, and {@code out[1]}. */
  private static void split(Node n, int offset, Node[] out) {
    if (n == null || offset == 0) {
      out[0] = null;
      out[1] = n;
      return;
    }
    if (offset == n.size) {
      out[0] = n;
      out[1] = null;
      return;
    }
    int ls = size(n.left);
    if (offset <= ls) {
      split(n.left, offset, out);
      out[1] = join(out[1], n, n.right);
      return;
    }
    int pe = ls + n.length;
    if (offset >= pe) {
      split(n.right, offset - pe, out);
      out[0] = join(n.left, n, out[0]);
      return;
    }
    int k = offset - ls;
    out[0] = join(n.left, Node.piece(n.chunk, n.start, k), null);
    out[1] = join(null, Node.piece(n.chunk, n.start + k, n.length - k), n.right);
  }

  /** Text a piece refers to. */
  private abstract static class Chunk {
    abstract char charAt(int i);

    /** Counts terminators in {@code [s, e)} taken on its own. */
    abstract int count(int s, int e);

    /** Returns the index of terminator {@code k} in {@code [s, e)} taken on its own. */
    abstract int find(int s, int e, int k);

    abstract void visit(ChunkVisitor v, int s, int e);
  }

  /** An immutable chunk with its terminator offsets. */
  private static final class StringChunk extends Chunk {
    final String text;
    final int[] terms;

    StringChunk(String text) {
      this.text = text;
      this.terms = LineBreaks.terminators(text);
    }

    @Override
    char charAt(int i) {
      return text.charAt(i);
    }

    @Override
    int count(int s, int e) {
      int n =
          LineBreaks.lowerBound(terms, 0, terms.length, e)
              - LineBreaks.lowerBound(terms, 0, terms.length, s);
      if (e > s && e < text.length() && text.charAt(e - 1) == '\r' && text.charAt(e) == '\n') {
        n++;
      }
      return n;
    }

    @Override
    int find(int s, int e, int k) {
      int i = LineBreaks.lowerBound(terms, 0, terms.length, s) + k;
      return i < terms.length && terms[i] < e ? terms[i] : e - 1;
    }

    @Override
    void visit(ChunkVisitor v, int s, int e) {
      v.chunk(text, s, e);
    }
  }

  /**
   * An append-only chunk. Chars below {@code used} never change, so a snapshot handed to another
   * thread reads them safely while the owner appends past them.
   */
  private static final class AddChunk extends Chunk {
    final char[] data = new char[CHUNK_CHARS];
    final CharBuffer view = CharBuffer.wrap(data).asReadOnlyBuffer();
    int used;

    int append(CharSequence text) {
      int at = used;
      int n = text.length();
      if (text instanceof String s) {
        s.getChars(0, n, data, at);
      } else {
        for (int i = 0; i < n; i++) {
          data[at + i] = text.charAt(i);
        }
      }
      used = at + n;
      return at;
    }

    @Override
    char charAt(int i) {
      return data[i];
    }

    @Override
    int count(int s, int e) {
      return LineBreaks.count(data, s, e);
    }

    @Override
    int find(int s, int e, int k) {
      return LineBreaks.find(data, s, e, k);
    }

    @Override
    void visit(ChunkVisitor v, int s, int e) {
      v.chunk(view, s, e);
    }
  }

  /** An immutable tree node: one piece plus the aggregates of its subtree. */
  private static final class Node {
    final Node left;
    final Node right;
    final Chunk chunk;
    final int start;
    final int length;
    final int breaks;
    final boolean firstLf;
    final boolean lastCr;
    final int height;
    final int size;
    final int lines;
    final boolean subFirstLf;
    final boolean subLastCr;

    Node(
        Node left,
        Chunk chunk,
        int start,
        int length,
        int breaks,
        boolean firstLf,
        boolean lastCr,
        Node right) {
      this.left = left;
      this.right = right;
      this.chunk = chunk;
      this.start = start;
      this.length = length;
      this.breaks = breaks;
      this.firstLf = firstLf;
      this.lastCr = lastCr;
      int sz = length;
      int ln = breaks;
      boolean first = firstLf;
      boolean lastIsCr = lastCr;
      if (left != null) {
        sz += left.size;
        ln += left.lines - (left.subLastCr && firstLf ? 1 : 0);
        first = left.subFirstLf;
      }
      if (right != null) {
        sz += right.size;
        ln += right.lines - (lastCr && right.subFirstLf ? 1 : 0);
        lastIsCr = right.subLastCr;
      }
      this.size = sz;
      this.lines = ln;
      this.subFirstLf = first;
      this.subLastCr = lastIsCr;
      this.height = 1 + Math.max(height(left), height(right));
    }

    static Node piece(Chunk c, int s, int len) {
      return new Node(
          null,
          c,
          s,
          len,
          c.count(s, s + len),
          c.charAt(s) == '\n',
          c.charAt(s + len - 1) == '\r',
          null);
    }

    Node with(Node l, Node r) {
      return new Node(l, chunk, start, length, breaks, firstLf, lastCr, r);
    }
  }

  /** The last piece {@link Text#charAt} read, kept so a sequential read does not descend. */
  private record Cursor(int start, int end, String str, char[] arr, int base) {}

  /** An immutable version of the text: a root node. */
  private static final class Text implements TextSnapshot {
    final Node root;
    private Cursor cursor;

    Text(Node root) {
      this.root = root;
    }

    @Override
    public int length() {
      return size(root);
    }

    @Override
    public char charAt(int index) {
      Cursor c = cursor;
      if (c == null || index < c.start || index >= c.end) {
        Objects.checkIndex(index, length());
        c = seek(index);
        cursor = c;
      }
      int i = index - c.base;
      return c.str != null ? c.str.charAt(i) : c.arr[i];
    }

    private Cursor seek(int index) {
      Node n = root;
      int base = 0;
      while (true) {
        int ls = size(n.left);
        if (index < base + ls) {
          n = n.left;
          continue;
        }
        int ps = base + ls;
        if (index < ps + n.length) {
          int shift = ps - n.start;
          if (n.chunk instanceof StringChunk s) {
            return new Cursor(ps, ps + n.length, s.text, null, shift);
          }
          return new Cursor(ps, ps + n.length, null, ((AddChunk) n.chunk).data, shift);
        }
        base = ps + n.length;
        n = n.right;
      }
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      final StringBuilder sb = new StringBuilder(end - start);
      readChunks(
          start,
          end,
          new ChunkVisitor() {
            @Override
            public void chunk(CharSequence chunk, int s, int e) {
              sb.append(chunk, s, e);
            }
          });
      return sb.toString();
    }

    @Override
    public String toString() {
      return subSequence(0, length()).toString();
    }

    @Override
    public int lineCount() {
      return root == null ? 1 : root.lines + 1;
    }

    @Override
    public int lineAt(int offset) {
      int len = length();
      Objects.checkIndex(offset, len + 1);
      int count = 0;
      boolean accCr = false;
      Node n = root;
      int o = offset;
      while (n != null && o > 0) {
        int ls = size(n.left);
        if (o <= ls) {
          n = n.left;
          continue;
        }
        if (n.left != null) {
          count += n.left.lines - (accCr && n.left.subFirstLf ? 1 : 0);
          accCr = n.left.subLastCr;
        }
        o -= ls;
        if (o <= n.length) {
          count += n.chunk.count(n.start, n.start + o) - (accCr && n.firstLf ? 1 : 0);
          break;
        }
        count += n.breaks - (accCr && n.firstLf ? 1 : 0);
        accCr = n.lastCr;
        o -= n.length;
        n = n.right;
      }
      if (offset > 0 && offset < len && charAt(offset - 1) == '\r' && charAt(offset) == '\n') {
        count--;
      }
      return count;
    }

    @Override
    public int offsetOfLine(int line) {
      Objects.checkIndex(line, lineCount());
      if (line == 0) {
        return 0;
      }
      int k = line - 1;
      Node n = root;
      int base = 0;
      boolean nextLf = false;
      while (true) {
        Node l = n.left;
        int effLeft = l == null ? 0 : l.lines - (l.subLastCr && n.firstLf ? 1 : 0);
        if (k < effLeft) {
          nextLf = n.firstLf;
          n = l;
          continue;
        }
        k -= effLeft;
        int ps = base + size(l);
        boolean pieceNextLf = n.right != null ? n.right.subFirstLf : nextLf;
        int effPiece = n.breaks - (n.lastCr && pieceNextLf ? 1 : 0);
        if (k < effPiece) {
          return ps + n.chunk.find(n.start, n.start + n.length, k) - n.start + 1;
        }
        k -= effPiece;
        base = ps + n.length;
        n = n.right;
      }
    }

    @Override
    public void readChunks(int start, int end, ChunkVisitor visitor) {
      Objects.checkFromToIndex(start, end, length());
      walk(root, 0, start, end, visitor);
    }

    private static void walk(Node n, int base, int s, int e, ChunkVisitor v) {
      if (n == null || s >= e) {
        return;
      }
      int ps = base + size(n.left);
      int pe = ps + n.length;
      if (s < ps) {
        walk(n.left, base, s, Math.min(e, ps), v);
      }
      int a = Math.max(s, ps);
      int b = Math.min(e, pe);
      if (a < b) {
        n.chunk.visit(v, n.start + a - ps, n.start + b - ps);
      }
      if (e > pe) {
        walk(n.right, pe, Math.max(s, pe), e, v);
      }
    }
  }
}
