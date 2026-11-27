package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;

/**
 * Commands a tool window runs itself when the main menus on it ({@link ToolMenuBars}) fire, set as
 * shell data under {@link #KEY} before the menus are built ({@link Dialogs#shell(org.eclipse.swt
 * .widgets.Shell, String, int, ToolCommands)}). Their items stay enabled whatever the main window's
 * state. Call on the UI thread.
 */
interface ToolCommands {
  /** Shell data of a tool window: its commands. */
  String KEY = ToolCommands.class.getName();

  /**
   * Reports whether the tool window runs a command.
   *
   * @param command a menu command
   * @return true when {@link #run} runs it
   */
  boolean runs(CommandId command);

  /**
   * Runs a command {@link #runs} accepts.
   *
   * @param command the command
   */
  void run(CommandId command);
}
