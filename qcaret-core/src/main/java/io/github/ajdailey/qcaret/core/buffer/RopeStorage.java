package io.github.ajdailey.qcaret.core.buffer;

import java.util.Objects;

/**
 * Persistent rope. Every node of an AVL tree holds one immutable {@code String} leaf of at most
 * {@value #LEAF_MAX} chars plus the length and line breaks of its subtree. An edit rebuilds the
 * leaves it touches (a leaf copy) and copies the path to them. {@link #snapshot()} is O(1): it
 * returns the current root.
 */
public final class RopeStorage implements TextStorage {
  static final int LEAF_MAX = 2048;

  private Text current;

  private RopeStorage(Node root) {
    current = new Text(root);
  }

  /**
   * Builds a storage holding a copy of {@code text}.
   *
   * @param text the initial text
   * @return the storage
   */
  public static RopeStorage of(CharSequence text) {
    return new RopeStorage(leaves(text));
  }

  @Override
  public void replace(int offset, int removeLength, CharSequence text) {
    Objects.checkFromIndexSize(offset, removeLength, length());
    Node root = current.root;
    int len = size(root);
    if (len == 0) {
      current = new Text(leaves(text));
      return;
    }
    int end = offset + removeLength;
    int a = leafStart(root, Math.min(offset, len - 1));
    int b = leafEnd(root, end > offset ? end - 1 : Math.min(offset, len - 1));
    int kept = (offset - a) + text.length() + (b - end);
    if (kept <= LEAF_MAX && kept >= LEAF_MAX / 4 && leafEnd(root, a) == b) {
      StringBuilder leaf = new StringBuilder(kept);
      current.append(leaf, a, offset);
      leaf.append(text);
      current.append(leaf, end, b);
      current = new Text(replaceLeaf(root, a, leaf.toString()));
      return;
    }
    if (kept < LEAF_MAX / 2 && b < len) {
      b = leafEnd(root, b);
    }
    StringBuilder sb = new StringBuilder(kept + LEAF_MAX);
    current.append(sb, a, offset);
    sb.append(text);
    current.append(sb, end, b);
    Node[] out = new Node[2];
    split(root, b, out);
    Node right = out[1];
    split(out[0], a, out);
    current = new Text(join2(join2(out[0], leaves(sb)), right));
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

  /** Splits text into leaves of near-equal length, at most {@value #LEAF_MAX} chars each. */
  private static Node leaves(CharSequence text) {
    int n = text.length();
    int count = (n + LEAF_MAX - 1) / LEAF_MAX;
    Node[] leaves = new Node[count];
    for (int i = 0; i < count; i++) {
      int s = (int) ((long) n * i / count);
      int e = (int) ((long) n * (i + 1) / count);
      leaves[i] = Node.leaf(text.subSequence(s, e).toString());
    }
    return build(leaves, 0, count);
  }

  private static Node build(Node[] leaves, int from, int to) {
    if (from >= to) {
      return null;
    }
    int mid = (from + to) >>> 1;
    return leaves[mid].with(build(leaves, from, mid), build(leaves, mid + 1, to));
  }

  /** Copies the path to the leaf holding the char at {@code index} and replaces that leaf. */
  private static Node replaceLeaf(Node n, int index, String text) {
    int ls = size(n.left);
    if (index < ls) {
      return n.with(replaceLeaf(n.left, index, text), n.right);
    }
    if (index < ls + n.text.length()) {
      return new Node(n.left, text, LineBreaks.count(text, 0, text.length()), n.right);
    }
    return n.with(n.left, replaceLeaf(n.right, index - ls - n.text.length(), text));
  }

  /** Returns the start offset of the leaf holding the char at {@code index}. */
  private static int leafStart(Node root, int index) {
    Node n = root;
    int base = 0;
    while (true) {
      int ls = size(n.left);
      if (index < base + ls) {
        n = n.left;
      } else if (index < base + ls + n.text.length()) {
        return base + ls;
      } else {
        base += ls + n.text.length();
        n = n.right;
      }
    }
  }

  /** Returns the end offset of the leaf holding the char at {@code index}. */
  private static int leafEnd(Node root, int index) {
    Node n = root;
    int base = 0;
    while (true) {
      int ls = size(n.left);
      if (index < base + ls) {
        n = n.left;
      } else if (index < base + ls + n.text.length()) {
        return base + ls + n.text.length();
      } else {
        base += ls + n.text.length();
        n = n.right;
      }
    }
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

  /** Joins two trees around the leaf of {@code m}; any height difference is allowed. */
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
    Node last = l;
    while (last.right != null) {
      last = last.right;
    }
    return join(removeLast(l), last, r);
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
    int pe = ls + n.text.length();
    if (offset >= pe) {
      split(n.right, offset - pe, out);
      out[0] = join(n.left, n, out[0]);
      return;
    }
    int k = offset - ls;
    out[0] = join(n.left, Node.leaf(n.text.substring(0, k)), null);
    out[1] = join(null, Node.leaf(n.text.substring(k)), n.right);
  }

  /** An immutable tree node: one leaf plus the aggregates of its subtree. */
  private static final class Node {
    final Node left;
    final Node right;
    final String text;
    final int breaks;
    final int height;
    final int size;
    final int lines;
    final boolean subFirstLf;
    final boolean subLastCr;

    Node(Node left, String text, int breaks, Node right) {
      this.left = left;
      this.right = right;
      this.text = text;
      this.breaks = breaks;
      int sz = text.length();
      int ln = breaks;
      boolean first = firstLf();
      boolean lastIsCr = lastCr();
      if (left != null) {
        sz += left.size;
        ln += left.lines - (left.subLastCr && firstLf() ? 1 : 0);
        first = left.subFirstLf;
      }
      if (right != null) {
        sz += right.size;
        ln += right.lines - (lastCr() && right.subFirstLf ? 1 : 0);
        lastIsCr = right.subLastCr;
      }
      this.size = sz;
      this.lines = ln;
      this.subFirstLf = first;
      this.subLastCr = lastIsCr;
      this.height = 1 + Math.max(height(left), height(right));
    }

    static Node leaf(String text) {
      return new Node(null, text, LineBreaks.count(text, 0, text.length()), null);
    }

    boolean firstLf() {
      return text.charAt(0) == '\n';
    }

    boolean lastCr() {
      return text.charAt(text.length() - 1) == '\r';
    }

    Node with(Node l, Node r) {
      return new Node(l, text, breaks, r);
    }
  }

  /** The last leaf {@link Text#charAt} read, kept so a sequential read does not descend. */
  private record Cursor(int start, int end, String leaf) {}

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
        int ps = leafStart(root, index);
        String leaf = leafAt(index);
        c = new Cursor(ps, ps + leaf.length(), leaf);
        cursor = c;
      }
      return c.leaf.charAt(index - c.start);
    }

    private String leafAt(int index) {
      Node n = root;
      int base = 0;
      while (true) {
        int ls = size(n.left);
        if (index < base + ls) {
          n = n.left;
        } else if (index < base + ls + n.text.length()) {
          return n.text;
        } else {
          base += ls + n.text.length();
          n = n.right;
        }
      }
    }

    void append(final StringBuilder sb, int start, int end) {
      readChunks(
          start,
          end,
          new ChunkVisitor() {
            @Override
            public void chunk(CharSequence chunk, int s, int e) {
              sb.append(chunk, s, e);
            }
          });
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      StringBuilder sb = new StringBuilder(end - start);
      append(sb, start, end);
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
        boolean firstLf = n.firstLf();
        if (o <= n.text.length()) {
          count += LineBreaks.count(n.text, 0, o) - (accCr && firstLf ? 1 : 0);
          break;
        }
        count += n.breaks - (accCr && firstLf ? 1 : 0);
        accCr = n.lastCr();
        o -= n.text.length();
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
        boolean firstLf = n.firstLf();
        int effLeft = l == null ? 0 : l.lines - (l.subLastCr && firstLf ? 1 : 0);
        if (k < effLeft) {
          nextLf = firstLf;
          n = l;
          continue;
        }
        k -= effLeft;
        int ps = base + size(l);
        boolean leafNextLf = n.right != null ? n.right.subFirstLf : nextLf;
        int effLeaf = n.breaks - (n.lastCr() && leafNextLf ? 1 : 0);
        if (k < effLeaf) {
          return ps + LineBreaks.find(n.text, 0, n.text.length(), k) + 1;
        }
        k -= effLeaf;
        base = ps + n.text.length();
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
      int pe = ps + n.text.length();
      if (s < ps) {
        walk(n.left, base, s, Math.min(e, ps), v);
      }
      int a = Math.max(s, ps);
      int b = Math.min(e, pe);
      if (a < b) {
        v.chunk(n.text, a - ps, b - ps);
      }
      if (e > pe) {
        walk(n.right, pe, Math.max(s, pe), e, v);
      }
    }
  }
}
