package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.util.List;

/**
 * The memory indicator's heap figures, in bytes. Immutable.
 *
 * @param used the live used heap, garbage included
 * @param max {@code Runtime.maxMemory()}, the heap {@link MemoryBudget} takes its share of
 * @param kept the figure the indicator shows; see {@link #of}
 */
record MemoryStatus(long used, long max, long kept) {
  /** Bytes per MB as the indicator counts them. */
  static final long MB = 1024L * 1024L;

  /**
   * A heap pool's figures.
   *
   * @param name the pool's name, such as {@code G1 Old Gen}
   * @param used its usage now
   * @param collected its usage after its last collection, or -1 when it reports none
   */
  record Pool(String name, long used, long collected) {}

  /**
   * Computes the shown figure from the heap pools. An old generation (a pool named {@code Old} or
   * {@code Tenured} that reports collection usage) gives the larger of its usage after its last
   * collection and its usage now: G1, Parallel and Serial update the first only at an old or
   * concurrent collection, so it reads 0 before one and misses promotions since. Without one, the
   * pools' collection usage summed (Shenandoah's single pool). When that is 0 or no pool reports
   * collection usage, the live used heap.
   *
   * @param used the live used heap
   * @param max the maximum heap
   * @param pools the heap pools
   * @return the figures
   */
  static MemoryStatus of(long used, long max, List<Pool> pools) {
    long collected = 0;
    for (Pool pool : pools) {
      if (pool.collected() >= 0) {
        if (pool.name().contains("Old") || pool.name().contains("Tenured")) {
          return new MemoryStatus(used, max, Math.max(pool.collected(), pool.used()));
        }
        collected += pool.collected();
      }
    }
    return new MemoryStatus(used, max, collected > 0 ? collected : used);
  }

  /**
   * Returns the status bar text.
   *
   * @return for example {@code 143/4096 MB}
   */
  String text() {
    return UiText.MEMORY_TEXT.format(kept / MB, max / MB);
  }

  /**
   * Returns the fill bar's share.
   *
   * @return the shown figure over the maximum, 0 to 1
   */
  double fill() {
    return max <= 0 ? 0 : Math.min(1.0, (double) kept / max);
  }

  /**
   * Returns the tool tip: the live used heap, the maximum, the open documents' share and how to
   * raise the maximum.
   *
   * @param budget the open documents' share
   * @return one line per figure
   */
  String tip(MemoryBudget budget) {
    long percent =
        budget.maxHeap() <= 0 ? 0 : Math.round(budget.limit() * 100.0 / budget.maxHeap());
    return String.join(
        "\n",
        UiText.MEMORY_TIP_USED.format(used / MB),
        UiText.MEMORY_TIP_MAX.format(max / MB),
        UiText.MEMORY_TIP_DOCUMENTS.format(budget.reserved() / MB, budget.limit() / MB, percent),
        UiText.MEMORY_TIP_RAISE.text(),
        UiText.MEMORY_TIP_CLICK.text());
  }
}
