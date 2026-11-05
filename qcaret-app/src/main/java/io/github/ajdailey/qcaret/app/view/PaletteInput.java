package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.util.List;

/** The command palette's controller as its view calls it. */
public interface PaletteInput {
  /**
   * Returns the rows for a query, best match first.
   *
   * @param query the filter text; empty lists every command
   * @return the rows
   */
  List<PaletteEntry> filter(String query);

  /**
   * Runs a chosen command when it is enabled; the view closes first.
   *
   * @param command the command
   */
  void run(CommandId command);
}
