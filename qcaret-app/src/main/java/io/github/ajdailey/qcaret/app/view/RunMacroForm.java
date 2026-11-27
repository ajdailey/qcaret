package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * Macros > Run Multiple Times' fields.
 *
 * @param macros the choices: the recorded macro's label first when there is one, then the saved
 *     macros' names
 * @param macro the chosen index in {@code macros}
 * @param count the count as typed
 * @param toEnd true to run to the end of the document instead of a count
 */
public record RunMacroForm(List<String> macros, int macro, String count, boolean toEnd) {
  /** Copies the choices. */
  public RunMacroForm {
    macros = List.copyOf(macros);
  }
}
