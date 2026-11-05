package io.github.ajdailey.qcaret.app.view;

/**
 * One row of the Quick Open popup.
 *
 * @param name the file name, or an untitled document's title
 * @param detail the path below the workspace folder, the whole path outside it, or empty for an
 *     untitled document
 * @param open true for an open document, shown in bold
 */
public record QuickOpenRow(String name, String detail, boolean open) {}
