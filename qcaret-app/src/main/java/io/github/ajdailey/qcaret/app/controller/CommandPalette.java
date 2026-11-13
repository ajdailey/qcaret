package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Fuzzy;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.view.PaletteEntry;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import java.util.ArrayList;
import java.util.List;

/**
 * The command palette: every menu command, filtered with {@link Fuzzy} on its title and then on its
 * menu and title, best score first and menu order among equals. A row shows the command's keys and
 * whether {@link CommandStates} enables it; only an enabled command runs. Confined to the UI
 * thread.
 */
final class CommandPalette implements PaletteInput {
  private final ApplicationController application;
  private final CommandStates states;
  private final Keymap keymap;

  CommandPalette(ApplicationController application, Keymap keymap) {
    this.application = application;
    this.states = new CommandStates(application);
    this.keymap = keymap;
  }

  @Override
  public List<PaletteEntry> filter(String query) {
    List<PaletteEntry> entries = new ArrayList<PaletteEntry>();
    List<Integer> scores = new ArrayList<Integer>();
    for (CommandGroup group : CommandGroup.values()) {
      if (!group.menu()) {
        continue;
      }
      for (CommandId command : CommandId.values()) {
        if (command.group() != group || command == CommandId.COMMAND_PALETTE) {
          continue;
        }
        String title = command.qualifiedTitle();
        int score = Fuzzy.score(query, title);
        if (score < 0) {
          score = Fuzzy.score(query, group.title() + " " + title);
        }
        if (score < 0) {
          continue;
        }
        int at = 0;
        while (at < scores.size() && scores.get(at) >= score) {
          at++;
        }
        scores.add(at, score);
        entries.add(
            at,
            new PaletteEntry(
                command, title, group.title(), keymap.keysText(command), states.enabled(command)));
      }
    }
    return entries;
  }

  @Override
  public void run(CommandId command) {
    if (states.enabled(command)) {
      application.execute(command);
    }
  }
}
