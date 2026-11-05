package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.search.TextMatcher;

/** Where the overview strips read the find and HyperSearch matches. Called on the UI thread. */
interface OverviewSources {
  /**
   * Returns the find bar's query while it marks a document.
   *
   * @param document the document
   * @return the matcher, or null when the bar is closed, has no valid query or finds in another
   *     document
   */
  TextMatcher findMatcher(Document document);

  /**
   * Returns the starts of the HyperSearch matches in a document, in its current revision.
   *
   * @param document the document
   * @return model offsets, any order; empty for none
   */
  int[] searchOffsets(Document document);
}
