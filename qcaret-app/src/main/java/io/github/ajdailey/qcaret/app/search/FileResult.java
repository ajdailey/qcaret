package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.PositionMap;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.file.Path;
import java.util.List;

/**
 * The matches of one source in a {@link ResultSet}: an open document, searched from a snapshot of a
 * revision, or a file, searched from its bytes. Confined to the UI thread once added to a set.
 *
 * <p>Staleness: a document group maps its matches to the document's current revision through {@link
 * Document#positions} in {@link #rebase}; a match an edit changed is marked stale, and a revision
 * older than the position log marks the whole group stale. A file group keeps the file's {@link
 * FileStamp} and SHA-256; {@link #markStale} records a changed file. A document group whose
 * document closes becomes a file group without a stamp ({@link #detach}).
 */
public final class FileResult {
  /** Heap per match beyond its snippet's chars: the hit, its fields and four array slots. */
  static final int MATCH_BYTES = 96;

  /** Heap per group beyond its matches. */
  static final int GROUP_BYTES = 256;

  private final Path path;
  private final String name;
  private final TextEncoding encoding;
  private final FileStamp stamp;
  private final byte[] digest;
  private final List<TextSearch.Hit> hits;
  private final int[] starts;
  private final int[] ends;
  private final int[] lines;
  private final boolean[] staleMatches;
  private final int skippedLines;
  private final int tooComplexLine;
  private Document document;
  private long revision;
  private long searched;
  private boolean stale;

  private FileResult(
      Path path,
      String name,
      Document document,
      long revision,
      TextEncoding encoding,
      FileStamp stamp,
      byte[] digest,
      List<TextSearch.Hit> hits,
      int skippedLines,
      int tooComplexLine) {
    this.path = path;
    this.name = name;
    this.document = document;
    this.revision = revision;
    this.searched = revision;
    this.encoding = encoding;
    this.stamp = stamp;
    this.digest = digest;
    this.hits = List.copyOf(hits);
    this.skippedLines = skippedLines;
    this.tooComplexLine = tooComplexLine;
    int n = hits.size();
    starts = new int[n];
    ends = new int[n];
    lines = new int[n];
    staleMatches = new boolean[n];
    for (int i = 0; i < n; i++) {
      TextSearch.Hit hit = hits.get(i);
      starts[i] = hit.start();
      ends[i] = hit.end();
      lines[i] = hit.line();
    }
  }

  /**
   * Creates a group for an open document.
   *
   * @param document the document
   * @param revision the revision searched
   * @param path its file, or null when untitled
   * @param name its tab name
   * @param hits the matches, at most {@code outcome}'s
   * @param outcome what the search gave
   * @return the group
   */
  static FileResult ofDocument(
      Document document,
      long revision,
      Path path,
      String name,
      List<TextSearch.Hit> hits,
      TextSearch.Outcome outcome) {
    return new FileResult(
        path,
        name,
        document,
        revision,
        document.encoding(),
        null,
        null,
        hits,
        outcome.skippedLines(),
        outcome.tooComplexLine());
  }

  /**
   * Creates a group for a file read from disk.
   *
   * @param path the file
   * @param encoding the encoding it was decoded with
   * @param stamp its stamp
   * @param digest the SHA-256 of the bytes searched
   * @param hits the matches, at most {@code outcome}'s
   * @param outcome what the search gave
   * @return the group
   */
  static FileResult ofFile(
      Path path,
      TextEncoding encoding,
      FileStamp stamp,
      byte[] digest,
      List<TextSearch.Hit> hits,
      TextSearch.Outcome outcome) {
    Path file = path.getFileName();
    return new FileResult(
        path,
        file == null ? path.toString() : file.toString(),
        null,
        -1,
        encoding,
        stamp,
        digest,
        hits,
        outcome.skippedLines(),
        outcome.tooComplexLine());
  }

  /**
   * Returns the heap the group holds, charged to the memory budget.
   *
   * @return bytes
   */
  long bytes() {
    long bytes = GROUP_BYTES;
    for (TextSearch.Hit hit : hits) {
      bytes += MATCH_BYTES + 2L * hit.snippet().length();
    }
    return bytes;
  }

  /**
   * Returns the file.
   *
   * @return the path, or null for an untitled document
   */
  public Path path() {
    return path;
  }

  /**
   * Returns the name shown.
   *
   * @return the file name, or the tab name of an untitled document
   */
  public String name() {
    return name;
  }

  /**
   * Returns the open document searched.
   *
   * @return the document, or null for a file group
   */
  public Document document() {
    return document;
  }

  /**
   * Returns the document revision the matches belong to: the one searched, or the one a file group
   * attached to.
   *
   * @return the revision, or -1 for a file group never attached
   */
  public long searchedRevision() {
    return searched;
  }

  /**
   * Returns the matches' offsets in the text searched, as ascending start and end pairs; once an
   * edit moved them, in the document's current revision.
   *
   * @return the offsets
   */
  int[] spans() {
    int[] spans = new int[2 * starts.length];
    for (int i = 0; i < starts.length; i++) {
      spans[2 * i] = starts[i];
      spans[2 * i + 1] = ends[i];
    }
    return spans;
  }

  /**
   * Returns the encoding the source was read in.
   *
   * @return the encoding
   */
  public TextEncoding encoding() {
    return encoding;
  }

  /**
   * Returns the file's stamp when searched.
   *
   * @return the stamp, or null for a document group or a detached one
   */
  public FileStamp stamp() {
    return stamp;
  }

  /**
   * Returns the SHA-256 of the bytes searched.
   *
   * @return 32 bytes, or null for a document group
   */
  public byte[] digest() {
    return digest == null ? null : digest.clone();
  }

  /**
   * Counts the matches.
   *
   * @return the count
   */
  public int matchCount() {
    return hits.size();
  }

  /**
   * Returns a match as searched: snippet, line, column and the hash of its text.
   *
   * @param match the index
   * @return the hit
   */
  public TextSearch.Hit hit(int match) {
    return hits.get(match);
  }

  /**
   * Returns a match's start in the document's current revision, or in the file's text.
   *
   * @param match the index
   * @return the offset
   */
  public int start(int match) {
    return starts[match];
  }

  /**
   * Returns the offset after a match.
   *
   * @param match the index
   * @return the offset
   */
  public int end(int match) {
    return ends[match];
  }

  /**
   * Returns a match's line in the document's current revision, or in the file.
   *
   * @param match the index
   * @return the line, from 0
   */
  public int line(int match) {
    return lines[match];
  }

  /**
   * Reports whether a match's text differs from the text searched, or the whole group is stale.
   *
   * @param match the index
   * @return true when stale
   */
  public boolean stale(int match) {
    return stale || staleMatches[match];
  }

  /**
   * Reports whether the group's source changed as a whole: the file changed, or the document's
   * edits passed the position log.
   *
   * @return true when stale
   */
  public boolean stale() {
    return stale;
  }

  /**
   * Counts the lines not searched for their length.
   *
   * @return the count
   */
  public int skippedLines() {
    return skippedLines;
  }

  /**
   * Returns the line where the regex overflowed the stack.
   *
   * @return the line, or -1
   */
  public int tooComplexLine() {
    return tooComplexLine;
  }

  /** Marks the group stale: its file changed. */
  public void markStale() {
    stale = true;
  }

  /**
   * Marks one match stale: navigation found other text at it.
   *
   * @param match the index
   */
  public void markStale(int match) {
    staleMatches[match] = true;
  }

  /**
   * Maps a document group's matches to the document's current revision. A match whose text then
   * differs in length or hash from the text searched is stale; past the position log the group is
   * stale and keeps its offsets.
   *
   * @return true when anything changed
   */
  public boolean rebase() {
    if (document == null || stale) {
      return false;
    }
    long current = document.revision();
    if (current == revision) {
      return false;
    }
    PositionMap map = document.positions();
    TextSnapshot text = document.text();
    for (int i = 0; i < starts.length; i++) {
      int s = map.map(revision, starts[i], Bias.AFTER);
      int e = map.map(revision, ends[i], Bias.BEFORE);
      if (s == PositionMap.UNMAPPABLE || e == PositionMap.UNMAPPABLE) {
        stale = true;
        revision = current;
        return true;
      }
      if (!staleMatches[i]
          && (e - s != ends[i] - starts[i]
              || text.subSequence(s, e).toString().hashCode() != hits.get(i).hash())) {
        staleMatches[i] = true;
      }
      starts[i] = s;
      ends[i] = Math.max(s, e);
      lines[i] = text.lineAt(Math.min(s, text.length()));
    }
    revision = current;
    return true;
  }

  /**
   * Makes a document group a file group after its document closed: navigation opens the file and
   * checks each match's text there.
   */
  public void detach() {
    rebase();
    document = null;
  }

  /**
   * Makes a file group a document group once its file opened with the searched text; the offsets
   * belong to the document's current revision.
   *
   * @param opened the document
   */
  public void attach(Document opened) {
    document = opened;
    revision = opened.revision();
    searched = revision;
  }
}
