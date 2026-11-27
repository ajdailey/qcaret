package io.github.ajdailey.qcaret.app.view;

/**
 * A hex view's vertical scroll state; the scroll bar values are in its {@code int} units.
 *
 * @param topRow the first visible row
 * @param maximum the scroll bar's maximum
 * @param thumb the thumb's size
 * @param selection the scroll bar's selection
 */
public record HexScroll(long topRow, int maximum, int thumb, int selection) {}
