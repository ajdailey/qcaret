package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.KeyStroke;

/**
 * Macros > Save Recorded Macro's fields.
 *
 * @param name the name as typed
 * @param key the key, or null for none
 */
public record SaveMacroForm(String name, KeyStroke key) {}
