package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.CommandId;

/**
 * One row of the command palette.
 *
 * @param command the command
 * @param title the command's title, after its cascade's title when it sits in one
 * @param group the menu's title
 * @param keys the keys as the platform writes them, empty when unbound
 * @param enabled true when the command can run now
 */
public record PaletteEntry(
    CommandId command, String title, String group, String keys, boolean enabled) {}
