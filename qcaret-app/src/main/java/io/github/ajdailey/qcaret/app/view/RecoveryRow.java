package io.github.ajdailey.qcaret.app.view;

/**
 * One row of the recovery dialog.
 *
 * @param name the file's path or the untitled title
 * @param time when the buffer was last written
 * @param state how the original file stands, or that the recovery file is damaged
 * @param damaged true when the row cannot be restored
 */
public record RecoveryRow(String name, String time, String state, boolean damaged) {}
