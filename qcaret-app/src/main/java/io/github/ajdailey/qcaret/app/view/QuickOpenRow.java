package io.github.ajdailey.qcaret.app.view;

/**
 * One row of the Quick Open popup.
 *
 * @param name the file name, or an untitled document's title
 * @param detail the path below the workspace folder, or the whole path outside it; an open
 *     document's is marked as open
 * @param open true for an open document
 */
public record QuickOpenRow(String name, String detail, boolean open) {}
