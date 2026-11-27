package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.MacroKeys;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroNames;
import io.github.ajdailey.qcaret.app.macro.MacroStore;
import io.github.ajdailey.qcaret.app.view.MacroManagerDisplay;
import io.github.ajdailey.qcaret.app.view.RunMacroForm;
import io.github.ajdailey.qcaret.app.view.SaveMacroForm;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A window's Macros menu commands that open a dialog, and its saved macros' menu items, palette
 * rows and keys: Run Multiple Times, Save Recorded Macro, Manage Macros ({@link MacroManager}) and
 * running a saved macro by name. Confined to the UI thread.
 */
final class MacroController {
  /** Largest Run Multiple Times count. */
  static final int MAX_COUNT = 999_999;

  private final ApplicationController window;
  private final MacroHooks hooks;

  MacroController(ApplicationController window, MacroHooks hooks) {
    this.window = window;
    this.hooks = hooks;
  }

  /** Runs a saved macro once in the selected editor; an unknown name shows a notice. */
  void runSaved(String name) {
    Macro macro = hooks.saved().macro(name);
    if (macro == null) {
      window.showNotice(UiText.MACRO_NONE.text());
      return;
    }
    hooks.play(window, macro, 1, null);
  }

  /** Macros > Run Multiple Times: asks for a macro and a count, then runs it. */
  void runMultiple() {
    final Macro recorded = hooks.recorded();
    final List<String> choices = new ArrayList<String>();
    if (recorded != null) {
      choices.add(UiText.MACRO_RECORDED_CHOICE.text());
    }
    final List<String> names = new ArrayList<String>();
    for (MacroKeys.Entry entry : hooks.saved().keys().entries()) {
      names.add(entry.name());
    }
    choices.addAll(names);
    if (choices.isEmpty()) {
      window.showNotice(UiText.MACRO_NONE.text());
      return;
    }
    window
        .view()
        .askRunMacro(
            new RunMacroForm(choices, 0, "1", false),
            new Function<RunMacroForm, String>() {
              @Override
              public String apply(RunMacroForm form) {
                return form.toEnd() || count(form.count()) > 0
                    ? null
                    : UiText.RUN_MACRO_COUNT_RANGE.format(MAX_COUNT);
              }
            },
            new Consumer<RunMacroForm>() {
              @Override
              public void accept(RunMacroForm form) {
                int index = form.macro() - (recorded == null ? 0 : 1);
                Macro macro = index < 0 ? recorded : hooks.saved().macro(names.get(index));
                int count = form.toEnd() ? 0 : count(form.count());
                if (macro != null && count >= 0) {
                  hooks.play(window, macro, count, null);
                }
              }
            });
  }

  /**
   * Parses a Run Multiple Times count.
   *
   * @param text the field
   * @return 1 to {@link #MAX_COUNT}, or -1
   */
  static int count(String text) {
    String digits = text.strip().replace(",", "");
    if (digits.isEmpty() || digits.length() > 7) {
      return -1;
    }
    for (int i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
        return -1;
      }
    }
    int value = Integer.parseInt(digits);
    return value >= 1 && value <= MAX_COUNT ? value : -1;
  }

  /** Macros > Save Recorded Macro: asks for a name and a key, then writes the macro's file. */
  void saveRecorded() {
    final Macro recorded = hooks.recorded();
    if (recorded == null) {
      window.showNotice(UiText.MACRO_NONE.text());
      return;
    }
    window
        .view()
        .askSaveMacro(
            new SaveMacroForm("", null),
            new Function<SaveMacroForm, String>() {
              @Override
              public String apply(SaveMacroForm form) {
                String problem = nameProblem(form.name(), hooks.saved().names(null));
                return problem != null || form.key() == null
                    ? problem
                    : window.keymap().macros().keyProblem(form.key(), form.name());
              }
            },
            new Consumer<SaveMacroForm>() {
              @Override
              public void accept(final SaveMacroForm form) {
                save(new Macro(form.name(), form.key(), recorded.steps()), null);
              }
            });
  }

  /** Macros > Manage Macros: lists the folder again, then opens the dialog. */
  void manage() {
    hooks
        .saved()
        .reload(
            new Runnable() {
              @Override
              public void run() {
                MacroManager manager = new MacroManager(MacroController.this, hooks.saved());
                manager.setDisplay(window.view().showManageMacros(manager));
              }
            });
  }

  /**
   * Writes a macro's file, lists the folder again and shows the result.
   *
   * @param macro the named macro
   * @param display the Manage Macros dialog, or null to show a notice when saved
   */
  void save(final Macro macro, final MacroManagerDisplay display) {
    hooks.saved().store().save(macro, changed(macro.name(), display));
  }

  /** Returns the end of a folder write: lists the folder again and reports a failure. */
  MacroStore.Done changed(final String saved, final MacroManagerDisplay display) {
    return new MacroStore.Done() {
      @Override
      public void done(final IOException failure) {
        hooks
            .saved()
            .reload(
                new Runnable() {
                  @Override
                  public void run() {
                    String text =
                        failure == null
                            ? null
                            : UiText.MACRO_FILE_FAILED.format(String.valueOf(failure.getMessage()));
                    if (display != null) {
                      display.rowsChanged();
                      if (text != null) {
                        display.showMessage(text);
                      }
                    } else if (text != null) {
                      window.showNotice(text);
                    } else if (saved != null) {
                      window.showNotice(UiText.MACRO_SAVED.format(saved));
                    }
                  }
                });
      }
    };
  }

  /** Runs a saved macro from Manage Macros. */
  void play(Macro macro) {
    hooks.play(window, macro, 1, null);
  }

  /**
   * Checks a key for a macro against the window's keymap.
   *
   * @param key the key
   * @param name the macro
   * @return why it is refused, or null
   */
  String keyProblem(KeyStroke key, String name) {
    return window.keymap().macros().keyProblem(key, name);
  }

  /**
   * Checks a macro name.
   *
   * @param name the name
   * @param taken the other macros' names
   * @return why it is refused, or null
   */
  static String nameProblem(String name, Collection<String> taken) {
    MacroNames.Problem problem = MacroNames.check(name, taken);
    if (problem == null) {
      return null;
    }
    return switch (problem) {
      case LENGTH -> UiText.MACRO_NAME_LENGTH.format(MacroNames.MAX_LENGTH);
      case CHARS -> UiText.MACRO_NAME_CHARS.text();
      case DEVICE -> UiText.MACRO_NAME_DEVICE.format(name);
      case TAKEN -> UiText.MACRO_NAME_TAKEN.format(taken(name, taken));
    };
  }

  private static String taken(String name, Collection<String> taken) {
    for (String other : taken) {
      if (MacroNames.same(name, other)) {
        return other;
      }
    }
    return name;
  }
}
