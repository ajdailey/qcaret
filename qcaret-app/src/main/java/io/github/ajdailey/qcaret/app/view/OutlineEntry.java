package io.github.ajdailey.qcaret.app.view;

/**
 * One heading of the Outline dock.
 *
 * @param text the heading's text without markup, at most 160 chars
 * @param level 1 to 6
 * @param parent the index of the closest earlier entry with a lower level, or -1 for a top entry
 * @param sourceLine the zero-based source line the heading starts on
 */
public record OutlineEntry(String text, int level, int parent, int sourceLine) {}
