package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.TextStatistics;

/**
 * Document Statistics' result for one document.
 *
 * @param name the document's name
 * @param encoding the name of the charset the bytes count in
 * @param document the whole text's counts, its byte order mark among the bytes
 * @param selection the sums over the non-empty selections, or null without one
 */
public record StatisticsReport(
    String name, String encoding, TextStatistics document, TextStatistics selection) {}
