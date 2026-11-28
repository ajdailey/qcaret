package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroRecorder;
import io.github.ajdailey.qcaret.app.macro.MacroStep;
import io.github.ajdailey.qcaret.app.macro.Recordability;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The macro recording and the macro runs of a {@link WindowSet}, and the hooks the controllers
 * report to. One recording runs at a time in the process; it belongs to the window that started it
 * and takes the steps of that window's selected editor. Stopping keeps it as the recorded macro
 * until a new recording replaces it. Each window runs at most one macro ({@link MacroPlayer});
 * while one runs, input to its document's views is dropped and the window runs only {@link
 * Recordability#SILENT} commands. The recording window's status bar shows the recording marker and
 * a run's window the run's progress ({@link MacroStatus}). It also holds the {@link SavedMacros}.
 * Confined to the UI thread.
 *
 * <p>A command is recorded once, by the first hook it passes: {@link #commandStarting} for {@link
 * ApplicationController#execute}, {@link #editorCommand} for a key the editor runs itself. A
 * disabled command is not recorded, so playback never fails on a step the recording ran as a no-op.
 */
final class MacroHooks {
  private final SavedMacros saved;
  private ApplicationController recordingWindow;
  private MacroRecorder recorder;
  private Macro recorded;
  private int dispatching;
  private final List<MacroPlayer> runs = new ArrayList<MacroPlayer>();

  /**
   * Creates the hooks.
   *
   * @param saved the saved macros
   */
  MacroHooks(SavedMacros saved) {
    this.saved = saved;
  }

  /** Creates a stand-in for a controller not yet in a window set: no saved macros. */
  MacroHooks() {
    this(new SavedMacros(null, null));
  }

  /**
   * Returns the saved macros.
   *
   * @return the list
   */
  SavedMacros saved() {
    return saved;
  }

  // Recording.

  /**
   * Reports whether a window records.
   *
   * @param window the window
   * @return true while its recording runs
   */
  boolean recording(ApplicationController window) {
    return recorder != null && recordingWindow == window;
  }

  /**
   * Returns the recorded macro.
   *
   * @return the last recording with a step, or null
   */
  Macro recorded() {
    return recorded;
  }

  /**
   * Stops the window's recording, or starts one there; a recording in another window stops first.
   *
   * @param window the window
   */
  void toggleRecording(ApplicationController window) {
    boolean here = recording(window);
    stopRecording();
    if (!here) {
      startRecording(window);
    }
  }

  /**
   * Starts recording a window's editing; refused while a macro runs there or the window has no
   * document.
   *
   * @param window the window
   * @return false when refused
   */
  boolean startRecording(final ApplicationController window) {
    if (recorder != null || run(window) != null) {
      window.showNotice(UiText.MACRO_BUSY.text());
      return false;
    }
    if (window.documents().activeEditor() == null) {
      return false;
    }
    recordingWindow = window;
    recorder =
        new MacroRecorder(
            new Consumer<String>() {
              @Override
              public void accept(String notice) {
                window.showNotice(notice);
              }
            });
    window.showNotice(UiText.MACRO_RECORDING.text());
    window.view().showMacroStatus(UiText.MACRO_STATUS_RECORDING.text());
    commandsChanged(window);
    return true;
  }

  /**
   * Stops the recording; one with a step becomes the recorded macro.
   *
   * @return the steps, or null when nothing recorded
   */
  Macro stopRecording() {
    if (recorder == null) {
      return null;
    }
    List<MacroStep> steps = recorder.steps();
    ApplicationController window = recordingWindow;
    recorder = null;
    recordingWindow = null;
    window.view().showMacroStatus("");
    Macro macro = new Macro(null, null, steps);
    if (!steps.isEmpty()) {
      recorded = macro;
    }
    commandsChanged(window);
    window.showNotice(
        UiText.MACRO_RECORDED.format(
            UiText.count(steps.size(), UiText.COUNT_STEP, UiText.COUNT_STEPS)));
    return macro;
  }

  // Playback.

  /**
   * Plays the recorded macro once in a window's selected editor.
   *
   * @param window the window
   */
  void playRecorded(ApplicationController window) {
    if (recorded == null) {
      window.showNotice(UiText.MACRO_NONE.text());
      return;
    }
    play(window, recorded, 1, null);
  }

  /**
   * Starts a macro in a window's selected editor; refused with a notice while the window records or
   * runs a macro, on a read-only document and for a macro without steps. The window's status bar
   * shows the run's progress.
   *
   * @param window the window
   * @param macro the macro
   * @param count the iterations, or 0 to run to the end of the document
   * @param listener receives progress and the end, or null
   * @return the run, or null when refused
   */
  MacroPlayer play(
      ApplicationController window, Macro macro, int count, MacroPlayer.Listener listener) {
    EditorController editor = window.documents().activeEditor();
    if (editor == null) {
      return null;
    }
    if (recording(window) || run(window) != null) {
      window.showNotice(UiText.MACRO_BUSY.text());
      return null;
    }
    MacroPlayer player =
        new MacroPlayer(window, editor, macro, count, this, new MacroStatus(window, listener));
    runs.add(player);
    if (!player.start()) {
      runs.remove(player);
      return null;
    }
    commandsChanged(window);
    return player;
  }

  /**
   * Reports whether a macro command is enabled in a window.
   *
   * @param window the window
   * @param command a {@link io.github.ajdailey.qcaret.app.command.CommandGroup#MACROS} command
   * @return true when it runs
   */
  boolean enabled(ApplicationController window, CommandId command) {
    if (command == CommandId.RECORD_MACRO && recording(window)) {
      return true;
    }
    Document document = window.documents().activeDocument();
    boolean free = run(window) == null && !recording(window);
    return switch (command) {
      case RECORD_MACRO -> free && document != null;
      case PLAY_RECORDED_MACRO -> recorded != null && savedEnabled(window);
      case RUN_MACRO_MULTIPLE ->
          (recorded != null || !saved.keys().entries().isEmpty()) && savedEnabled(window);
      case SAVE_RECORDED_MACRO -> recorded != null && !recording(window);
      case MANAGE_MACROS -> run(window) == null;
      default -> true;
    };
  }

  /**
   * Reports whether a saved macro runs in a window: no recording or run there, and a selected
   * document that is not read-only.
   *
   * @param window the window
   * @return true when a saved macro's menu item is enabled
   */
  boolean savedEnabled(ApplicationController window) {
    Document document = window.documents().activeDocument();
    return run(window) == null && !recording(window) && document != null && !document.isReadOnly();
  }

  /** Returns a window's run, or null. */
  MacroPlayer run(ApplicationController window) {
    for (MacroPlayer player : runs) {
      if (player.window() == window) {
        return player;
      }
    }
    return null;
  }

  private MacroPlayer runOn(Document document) {
    for (MacroPlayer player : runs) {
      if (player.document() == document) {
        return player;
      }
    }
    return null;
  }

  /**
   * Reports whether a run holds a document's input: true while it runs and is not running a step.
   *
   * @param document the document
   * @return true when view input to it is dropped
   */
  boolean held(Document document) {
    MacroPlayer player = runOn(document);
    return player != null && !player.stepping();
  }

  /**
   * Reports whether a macro runs on a document.
   *
   * @param document the document
   * @return true while a run is active
   */
  boolean playing(Document document) {
    return runOn(document) != null;
  }

  /** Stops the run on a document: Esc in one of its views. */
  void cancelRun(Document document) {
    MacroPlayer player = runOn(document);
    if (player != null) {
      player.cancel();
    }
  }

  /** Reports whether a window's run is inside a step, whose commands the window lets through. */
  boolean stepping(ApplicationController window) {
    MacroPlayer player = run(window);
    return player != null && player.stepping();
  }

  /** Shows that a command another pane took, the hex pane, is not recorded. */
  void notRecorded(ApplicationController window, CommandId command) {
    if (recording(window)) {
      recorder.notRecorded(command);
    }
  }

  /** Forgets a run that ended. */
  void runEnded(MacroPlayer player) {
    runs.remove(player);
    commandsChanged(player.window());
  }

  /**
   * Syncs the window's command states. A sync while a recording or run is active disables the macro
   * items (the Play item syncs its menu after it runs, and a run's first edit changes the dirty
   * state), and a disabled item's accelerator does not fire.
   */
  private static void commandsChanged(ApplicationController window) {
    window.view().documentStateChanged();
  }

  /** Stops a closing window's recording and run, without a notice for the run. */
  void windowStopped(ApplicationController window) {
    if (recording(window)) {
      stopRecording();
    }
    MacroPlayer player = run(window);
    if (player != null) {
      player.abandon();
    }
  }

  // Hooks.

  /**
   * Reports whether a window drops a command: while its macro runs, everything but a {@link
   * Recordability#SILENT} command is dropped, and {@link CommandId#CANCEL} stops the run.
   *
   * @param window the window
   * @param command the command
   * @return true to drop it
   */
  boolean drops(ApplicationController window, CommandId command) {
    MacroPlayer player = run(window);
    if (player == null || player.stepping()) {
      return false;
    }
    if (command == CommandId.CANCEL) {
      player.cancel();
    }
    return Recordability.of(command) != Recordability.SILENT;
  }

  /**
   * Records a command {@link ApplicationController#execute} is about to run; the editor's own hook
   * then records nothing until {@link #commandEnded}.
   *
   * @param window the window
   * @param command the command
   */
  void commandStarting(ApplicationController window, CommandId command) {
    if (dispatching++ == 0 && recording(window)) {
      record(window, command);
    }
  }

  /** Ends a command {@link #commandStarting} reported. */
  void commandEnded(ApplicationController window) {
    dispatching--;
  }

  /**
   * Records a command an editor is about to run from its own keys.
   *
   * @param editor the editor
   * @param command the command
   */
  void editorCommand(EditorController editor, CommandId command) {
    if (dispatching == 0 && records(editor)) {
      record(recordingWindow, command);
    }
  }

  private void record(ApplicationController window, CommandId command) {
    Recordability kind = Recordability.of(command);
    if (kind == Recordability.NOTICE
        || (kind == Recordability.COMMAND && new CommandStates(window).enabled(command))) {
      recorder.command(command);
    }
  }

  /** Records typed text. */
  void typed(EditorController editor, String text) {
    if (records(editor)) {
      recorder.typed(text);
    }
  }

  /**
   * Records text the view replaced on its own: an input method's commit, or a mouse drop or paste,
   * which is left out with a notice.
   *
   * @param editor the editor
   * @param text the new text
   * @param composing true when an input method's composition showed
   */
  void viewReplaced(EditorController editor, String text, boolean composing) {
    if (!records(editor)) {
      return;
    }
    if (composing) {
      recorder.inserted(text);
    } else {
      recorder.mouse();
    }
  }

  /** Records a chosen completion. */
  void completed(EditorController editor, String word) {
    if (records(editor)) {
      recorder.completed(word);
    }
  }

  /** Shows that a mouse action in an editor is not recorded. */
  void mouse(EditorController editor) {
    if (records(editor)) {
      recorder.mouse();
    }
  }

  /** Records a find of the find bar. */
  void found(DocumentController documents, MacroStep.Find step) {
    if (records(documents)) {
      recorder.found(step);
    }
  }

  /** Records a Replace or Replace All of the find bar. */
  void replaced(DocumentController documents, MacroStep.Replace step) {
    if (records(documents)) {
      recorder.replaced(step);
    }
  }

  /** Records Go to Line's answer, both from 1. */
  void lineChosen(DocumentController documents, int line, int column) {
    if (records(documents)) {
      recorder.goToLine(line, column);
    }
  }

  /** Records Tab Width > Other's answer. */
  void tabWidthChosen(DocumentController documents, int columns) {
    if (records(documents)) {
      recorder.tabWidth(columns);
    }
  }

  /** Records Insert Date and Time's format. */
  void dateTimeChosen(DocumentController documents, String pattern) {
    if (records(documents)) {
      recorder.dateTime(pattern);
    }
  }

  /** Records Insert Number Sequence's values. */
  void sequenceChosen(DocumentController documents, NumberSequence sequence) {
    if (records(documents)) {
      recorder.sequence(sequence);
    }
  }

  /** Fails the step a run's editor ran: a worker command ended with an error notice. */
  void stepFailed(EditorController editor, String notice) {
    MacroPlayer player = runOn(editor.document());
    if (player != null && player.editor() == editor) {
      player.failed(notice);
    }
  }

  private boolean records(EditorController editor) {
    return recorder != null && recordingWindow.documents().activeEditor() == editor;
  }

  private boolean records(DocumentController documents) {
    return recorder != null && recordingWindow.documents() == documents;
  }
}
