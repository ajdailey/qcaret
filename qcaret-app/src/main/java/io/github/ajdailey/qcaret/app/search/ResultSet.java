package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.document.Document;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One HyperSearch's results: the request, its state, the file groups and what was skipped. Confined
 * to the UI thread; {@link HyperSearch} fills it. The groups' heap is reserved from the {@link
 * MemoryBudget} as they arrive and returned by {@link #release}.
 */
public final class ResultSet {
  /** How the search stands. */
  public enum State {
    /** Running. */
    RUNNING,
    /** Searched everything. */
    DONE,
    /** Stopped by the user; holds what was found before. */
    CANCELED,
    /** Stopped at the match limit or a refused memory reservation. */
    TRUNCATED
  }

  private final long id;
  private final SearchRequest request;
  private final long started;
  private final List<FileResult> files = new ArrayList<FileResult>();
  private State state = State.RUNNING;
  private int matches;
  private int searched;
  private int total = -1;
  private int binary;
  private int tooLarge;
  private int unreadable;
  private int skippedLines;
  private int tooComplex;
  private boolean noMemory;
  private boolean pinned;
  private long reserved;
  private MemoryBudget budget;

  /**
   * Creates a running set.
   *
   * @param id the set's id
   * @param request the search
   * @param started the start time, in milliseconds since the epoch
   */
  public ResultSet(long id, SearchRequest request, long started) {
    this.id = id;
    this.request = request;
    this.started = started;
  }

  /**
   * Returns the id.
   *
   * @return the id
   */
  public long id() {
    return id;
  }

  /**
   * Returns the request.
   *
   * @return the request
   */
  public SearchRequest request() {
    return request;
  }

  /**
   * Returns the start time.
   *
   * @return milliseconds since the epoch
   */
  public long started() {
    return started;
  }

  /**
   * Returns the state.
   *
   * @return the state
   */
  public State state() {
    return state;
  }

  /**
   * Returns the file groups, open documents first in tab order, then files by path once the search
   * ends; in arrival order while it runs.
   *
   * @return the groups, unmodifiable
   */
  public List<FileResult> files() {
    return Collections.unmodifiableList(files);
  }

  /**
   * Counts the matches.
   *
   * @return the count
   */
  public int matches() {
    return matches;
  }

  /**
   * Counts the files searched or skipped, open documents included.
   *
   * @return the count
   */
  public int searched() {
    return searched;
  }

  /**
   * Counts the files to search.
   *
   * @return the count, or -1 while the walk runs
   */
  public int total() {
    return total;
  }

  /**
   * Counts the binary files skipped.
   *
   * @return the count
   */
  public int binary() {
    return binary;
  }

  /**
   * Counts the files skipped for their size.
   *
   * @return the count
   */
  public int tooLarge() {
    return tooLarge;
  }

  /**
   * Counts the files and directories that could not be read, or found no memory for their read.
   *
   * @return the count
   */
  public int unreadable() {
    return unreadable;
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
   * Counts the sources where the regex overflowed the stack.
   *
   * @return the count
   */
  public int tooComplex() {
    return tooComplex;
  }

  /**
   * Reports whether the memory budget refused the results.
   *
   * @return true when results were dropped for memory
   */
  public boolean noMemory() {
    return noMemory;
  }

  /**
   * Reports whether the set is kept past the retained limit.
   *
   * @return true when pinned
   */
  public boolean pinned() {
    return pinned;
  }

  /**
   * Pins or unpins the set.
   *
   * @param value true to pin
   */
  public void setPinned(boolean value) {
    pinned = value;
  }

  /**
   * Returns the heap reserved for the groups.
   *
   * @return bytes
   */
  public long reserved() {
    return reserved;
  }

  /** Adds an arrived group whose heap the budget holds. */
  void add(FileResult file, long bytes, MemoryBudget from) {
    files.add(file);
    matches += file.matchCount();
    skippedLines += file.skippedLines();
    if (file.tooComplexLine() >= 0) {
      tooComplex++;
    }
    reserved += bytes;
    budget = from;
  }

  /**
   * Replaces a group with the result of searching its source again, or removes it.
   *
   * @param old the group
   * @param next the new group, or null to remove it
   * @param bytes the heap {@code from} holds for {@code next}
   * @param from the budget charged for the groups
   * @return false when {@code old} is not in the set
   */
  boolean replace(FileResult old, FileResult next, long bytes, MemoryBudget from) {
    int at = files.indexOf(old);
    if (at < 0) {
      return false;
    }
    matches -= old.matchCount();
    skippedLines -= old.skippedLines();
    if (old.tooComplexLine() >= 0) {
      tooComplex--;
    }
    long freed = old.bytes();
    if (budget != null && reserved > 0) {
      budget.release(Math.min(freed, reserved));
    }
    reserved = Math.max(0, reserved - freed);
    if (next == null) {
      files.remove(at);
    } else {
      files.set(at, next);
      matches += next.matchCount();
      skippedLines += next.skippedLines();
      if (next.tooComplexLine() >= 0) {
        tooComplex++;
      }
      reserved += bytes;
      budget = from;
    }
    return true;
  }

  /** Sets the counts read from the workers. */
  void progress(
      int searchedFiles,
      int totalFiles,
      int binaryFiles,
      int largeFiles,
      int unreadableFiles,
      boolean memory) {
    searched = searchedFiles;
    total = totalFiles;
    binary = binaryFiles;
    tooLarge = largeFiles;
    unreadable = unreadableFiles;
    noMemory = memory;
  }

  /** Ends the search and orders the groups: open documents in tab order first, then by path. */
  void finish(State end, final List<Document> documentOrder) {
    state = end;
    files.sort(
        new Comparator<FileResult>() {
          @Override
          public int compare(FileResult a, FileResult b) {
            int ia = rank(a);
            int ib = rank(b);
            if (ia != ib) {
              return Integer.compare(ia, ib);
            }
            return ia == Integer.MAX_VALUE ? key(a).compareTo(key(b)) : 0;
          }

          private String key(FileResult f) {
            return f.path() == null ? f.name() : f.path().toString();
          }

          private int rank(FileResult f) {
            int at = f.document() == null ? -1 : documentOrder.indexOf(f.document());
            return at < 0 ? Integer.MAX_VALUE : at;
          }
        });
  }

  /** Returns the reserved heap to the budget; the groups stay. Idempotent. */
  public void release() {
    if (budget != null && reserved > 0) {
      budget.release(reserved);
    }
    reserved = 0;
  }
}
