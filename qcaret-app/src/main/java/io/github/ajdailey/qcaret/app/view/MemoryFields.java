package io.github.ajdailey.qcaret.app.view;

/**
 * The status bar's memory indicator as shown.
 *
 * @param text the heap kept after collection and the maximum, such as {@code 143/4096 MB}
 * @param tip the tool tip, one figure per line
 * @param fill the bar's filled share, 0 to 1
 */
public record MemoryFields(String text, String tip, double fill) {}
