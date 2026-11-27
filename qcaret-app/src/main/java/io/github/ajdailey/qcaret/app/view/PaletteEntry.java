package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.CommandId;

/**
 * One row of the command palette: a command, or a saved macro.
 *
 * @param command the command, or null for a saved macro's row
 * @param title the command's title, after its cascade's title when it sits in one
 * @param group the menu's title
 * @param keys the keys as the platform writes them, empty when unbound
 * @param enabled true when the command can run now
 * @param macro the saved macro's name, or null for a command's row
 */
public record PaletteEntry(
    CommandId command, String title, String group, String keys, boolean enabled, String macro) {}
