package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.SequenceForm;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.edit.BlockEdits;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Edit > Insert Number Sequence: asks for the values, starting from {@code ui.numberSequence.*},
 * then inserts one number at each caret or block row of the selected editor and saves the values. A
 * number outside 64 bits inserts nothing and shows a notice. Confined to the UI thread.
 */
final class NumberSequenceInsert {
  private final DocumentController documents;
  private final Settings settings;
  private final MainView view;

  NumberSequenceInsert(DocumentController documents, Settings settings, MainView view) {
    this.documents = documents;
    this.settings = settings;
    this.view = view;
  }

  /** Asks for the values when an editable document is selected. */
  void choose() {
    EditorController editor = documents.activeEditor();
    if (editor == null || editor.document().isReadOnly()) {
      return;
    }
    view.askNumberSequence(
        form(settings.current().state().numberSequence()),
        new Predicate<SequenceForm>() {
          @Override
          public boolean test(SequenceForm form) {
            return sequence(form) != null;
          }
        },
        new Consumer<SequenceForm>() {
          @Override
          public void accept(SequenceForm form) {
            NumberSequence sequence = sequence(form);
            if (sequence != null) {
              insert(sequence);
            }
          }
        });
  }

  /** Returns the dialog fields of a sequence. */
  static SequenceForm form(NumberSequence sequence) {
    return new SequenceForm(
        Long.toString(sequence.start()),
        Long.toString(sequence.step()),
        sequence.width(),
        sequence.base().ordinal());
  }

  /**
   * Returns the sequence the dialog fields give.
   *
   * @param form the fields
   * @return the sequence, or null when the start or the step is not a number in 64 bits, or the
   *     width or base is out of range
   */
  static NumberSequence sequence(SequenceForm form) {
    Long start = NumberSequence.parse(form.start());
    Long step = NumberSequence.parse(form.step());
    NumberSequence.Base[] bases = NumberSequence.Base.values();
    if (start == null
        || step == null
        || form.width() < 0
        || form.width() > NumberSequence.MAX_WIDTH
        || form.base() < 0
        || form.base() >= bases.length) {
      return null;
    }
    return new NumberSequence(start, step, form.width(), bases[form.base()]);
  }

  /**
   * Inserts a sequence in the selected editor and remembers its values.
   *
   * @param sequence the values
   */
  void insert(NumberSequence sequence) {
    Preferences current = settings.current();
    if (!sequence.equals(current.state().numberSequence())) {
      settings.set(current.withState(current.state().withNumberSequence(sequence)));
    }
    EditorController editor = documents.activeEditor();
    if (editor == null) {
      return;
    }
    try {
      insertInto(editor, sequence);
    } catch (ArithmeticException e) {
      view.showNotice(UiText.NUMBER_SEQUENCE_OVERFLOW.text());
    }
  }

  /**
   * Inserts one number of a sequence at each selection, in text order, or on each block row from
   * the top, as one undo step.
   *
   * @param editor the editor
   * @param sequence the numbers
   * @throws ArithmeticException when a number lies outside {@code long}; nothing changes
   */
  private static void insertInto(EditorController editor, NumberSequence sequence) {
    Document document = editor.document();
    if (document.isReadOnly()) {
      return;
    }
    BlockSelection block = editor.selections().block();
    if (block == null) {
      editor.replaceSelections(sequence.values(editor.selections().selections().size()));
      return;
    }
    document.clearPreedit();
    TextSnapshot text = document.text();
    int tabWidth = document.tabWidth();
    int rows = block.rows(text, tabWidth).size();
    editor.applyEdit(BlockEdits.paste(text, block, tabWidth, sequence.values(rows)));
  }
}
