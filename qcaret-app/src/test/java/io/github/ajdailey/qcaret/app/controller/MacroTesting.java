package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** A {@link Harness} with helpers to record, settle and play macros, and to compare editors. */
final class MacroTesting {
  final Harness harness;
  final ApplicationController app;
  final FindController find;
  private final Path temp;
  private int files;

  MacroTesting(Path temp) {
    this.temp = temp;
    harness = new Harness(temp);
    app = harness.application;
    find = app.find();
  }

  MacroHooks macros() {
    return app.windowSet().macros();
  }

  /** Opens a new file with a text; returns its editor, caret at the start. */
  EditorController open(String text) throws Exception {
    Path file = temp.resolve("macro" + files++ + ".txt");
    harness.open(Files.writeString(file, text, StandardCharsets.UTF_8));
    EditorController editor = app.documents().activeEditor();
    editor.viewportChanged(0, editor.projection().length());
    return editor;
  }

  /** Runs UI tasks until no worker command, find or replace runs for the editor. */
  void settle(final EditorController editor) throws InterruptedException {
    harness.ui.runPending();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.busyCursor().active() == 0 && !find.state().running();
          }
        },
        "editor settles");
    harness.ui.runPending();
  }

  /** Plays a macro in the selected editor and runs UI tasks until it ends. */
  MacroPlayer play(Macro macro, int count) throws InterruptedException {
    MacroPlayer run = macros().play(app, macro, count, null);
    assertNotNull(run, "run started");
    finish(run);
    return run;
  }

  /** Runs UI tasks until a run ends. */
  void finish(MacroPlayer run) throws InterruptedException {
    harness.await(run::ended, "run ends");
    harness.ui.runPending();
  }

  /** Returns the last notice the window showed. */
  String lastNotice() {
    return harness.view.notices.get(harness.view.notices.size() - 1);
  }

  static String text(EditorController editor) {
    TextSnapshot text = editor.document().text();
    return text.subSequence(0, text.length()).toString();
  }

  /** Fails unless two editors hold the same text, selections and block. */
  static void assertSameState(EditorController expected, EditorController actual, String what) {
    assertEquals(text(expected), text(actual), what + ": text");
    SelectionSet want = expected.selections().selections();
    assertEquals(want, actual.selections().selections(), what + ": selections");
    BlockSelection block = expected.selections().block();
    assertEquals(block, actual.selections().block(), what + ": block");
  }

  void close() throws InterruptedException {
    app.documents().dispose();
    harness.close();
  }
}
