package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.StatusFields;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;

/** The status bar's fields for {@link CommandStates#status}. Call on the UI thread. */
final class StatusBarText {
  private StatusBarText() {}

  /** Reads the fields of a window's selected document; see {@link CommandStates#status}. */
  static StatusFields read(DocumentController documents) {
    Document document = documents.activeDocument();
    EditorController editor = documents.activeEditor();
    if (document == null || editor == null) {
      return StatusFields.NONE;
    }
    TextSnapshot text = document.text();
    SelectionSet set = editor.selections().selections();
    int caret = set.primary().caret();
    int line = text.lineAt(caret);
    LineView view = text.lineView(line, new LineView());
    int index = Math.min(caret - view.start(), view.length());
    int column = Columns.column(view, index, document.tabWidth());
    long selected = 0;
    for (Selection s : set.selections()) {
      selected += s.end() - s.start();
    }
    String selection =
        set.size() > 1
            ? UiText.STATUS_SELECTION_CARETS.format(selected, set.size())
            : selected > 0 ? UiText.STATUS_SELECTION.format(selected) : "";
    TextEncoding encoding = document.encoding();
    String charset = encoding.charset().name();
    String readOnly =
        documents.activeBinary()
            ? UiText.STATUS_BINARY.text()
            : document.isReadOnly() ? UiText.STATUS_READ_ONLY.text() : "";
    return new StatusFields(
        UiText.STATUS_POSITION.format(line + 1, column + 1),
        selection,
        encoding.bom() ? UiText.STATUS_BOM.format(charset) : charset,
        endingText(document.lineEndings(), document.lineEnding()),
        language(documents.activeGrammar()),
        readOnly);
  }

  private static String language(Grammar grammar) {
    return grammar == null || GrammarSet.PLAIN.equals(grammar.id())
        ? UiText.STATUS_PLAIN_TEXT.text()
        : grammar.name();
  }

  private static String endingText(LineEndingStats stats, LineEnding inserted) {
    if (stats.isMixed()) {
      return UiText.STATUS_MIXED.text();
    }
    return switch (stats.dominant(inserted)) {
      case CRLF -> UiText.STATUS_CRLF.text();
      case LF -> UiText.STATUS_LF.text();
      case CR -> UiText.STATUS_CR.text();
    };
  }
}
