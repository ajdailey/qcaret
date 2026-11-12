package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.Fuzzy;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.QuickOpenRow;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.syntax.SymbolScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Quick Open's symbol rows: after {@code @}, the selected document's symbols that {@link Fuzzy}
 * matches on their names, best first and by line among equals, at most {@value #MAX_ROWS}; an empty
 * symbol query lists them by line. The symbols come from the window's {@link SymbolCache}, kept or
 * scanned; a result read at an older revision than the document's is dropped and requested again,
 * at most {@value #ATTEMPTS} times per popup. A row's detail is the kind and the line. Choosing a
 * row records the caret in the navigation history and moves it to the name's start, the line and
 * column clamped to the current text. Confined to the UI thread.
 *
 * <p>{@link #jumpToBest} serves {@code file@symbol}: it requests the symbols of the document opened
 * for the file and moves the caret to the best match. The jump is dropped when the document
 * changed, the caret moved or the editor closed during the scan.
 */
final class SymbolRows {
  /** Most rows listed. */
  static final int MAX_ROWS = 500;

  /** Scans started for one popup before a changing document is reported. */
  static final int ATTEMPTS = 3;

  private final DocumentController documents;
  private final GrammarLibrary grammars;
  private final MainView view;
  private final Runnable changed;
  private EditorController editor;
  private Object running;
  private int attempts;
  private DocumentSymbols.Scan scan;
  private String query = "";
  private List<SymbolScanner.Symbol> matched = List.of();
  private List<QuickOpenRow> rows = List.of();
  private boolean closed;

  SymbolRows(
      DocumentController documents, GrammarLibrary grammars, MainView view, Runnable changed) {
    this.documents = documents;
    this.grammars = grammars;
    this.view = view;
    this.changed = changed;
  }

  /**
   * Matches a symbol query against the selected document, scanning it first.
   *
   * @param text the text after {@code @}
   */
  void filter(String text) {
    query = text;
    EditorController active = documents.activeEditor();
    if (active != editor) {
      running = null;
      editor = active;
      scan = null;
      attempts = 0;
    }
    if (editor != null && scan == null && running == null && attempts < ATTEMPTS && !closed) {
      startScan();
    }
    match();
  }

  private void startScan() {
    attempts++;
    final Object ticket = new Object();
    running = ticket;
    DocumentSymbols.Scan kept =
        documents
            .symbolCache()
            .request(
                editor,
                grammars,
                documents.pathOf(editor.document()),
                new Consumer<DocumentSymbols.Scan>() {
                  @Override
                  public void accept(DocumentSymbols.Scan result) {
                    scanned(ticket, result);
                  }
                });
    if (kept != null) {
      running = null;
      scan = kept;
    }
  }

  private void scanned(Object ticket, DocumentSymbols.Scan result) {
    if (closed || ticket != running) {
      return;
    }
    running = null;
    if (editor.disposed()) {
      return;
    }
    if (result.revision() == editor.document().revision()) {
      scan = result;
    } else if (attempts < ATTEMPTS) {
      startScan();
    }
    match();
    changed.run();
  }

  private void match() {
    if (scan == null) {
      matched = List.of();
      rows = List.of();
      return;
    }
    List<SymbolScanner.Symbol> all = scan.result().symbols();
    List<int[]> keys = new ArrayList<int[]>();
    for (int i = 0; i < all.size(); i++) {
      int score = Fuzzy.score(query, all.get(i).name());
      if (score >= 0) {
        keys.add(new int[] {score, i});
      }
    }
    keys.sort(
        new Comparator<int[]>() {
          @Override
          public int compare(int[] a, int[] b) {
            return a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[1], b[1]);
          }
        });
    int count = Math.min(MAX_ROWS, keys.size());
    List<SymbolScanner.Symbol> symbols = new ArrayList<SymbolScanner.Symbol>(count);
    List<QuickOpenRow> list = new ArrayList<QuickOpenRow>(count);
    for (int i = 0; i < count; i++) {
      SymbolScanner.Symbol s = all.get(keys.get(i)[1]);
      symbols.add(s);
      list.add(
          new QuickOpenRow(
              s.name(), UiText.SYMBOL_DETAIL.format(s.kind().label(), s.line() + 1), false));
    }
    matched = symbols;
    rows = list;
  }

  /**
   * Returns the rows of the last match.
   *
   * @return best first
   */
  List<QuickOpenRow> rows() {
    return rows;
  }

  /**
   * Returns the line below the rows.
   *
   * @return the match count, or what the scan is doing
   */
  String summary() {
    if (editor == null) {
      return UiText.SYMBOL_NO_DOCUMENT.text();
    }
    String name = documents.nameOf(editor.document());
    if (scan == null) {
      return running != null
          ? UiText.SYMBOL_SCANNING.format(name)
          : UiText.SYMBOL_CHANGING.format(name);
    }
    if (scan.grammar() == null) {
      return UiText.SYMBOL_PLAIN.format(name);
    }
    if (!scan.rules()) {
      return UiText.SYMBOL_NO_RULES.format(name, scan.grammar());
    }
    String count =
        UiText.SYMBOL_COUNT.format(
            matched.size(),
            UiText.count(scan.result().symbols().size(), UiText.COUNT_SYMBOL, UiText.COUNT_SYMBOLS),
            name);
    if (scan.result().truncated()) {
      count = UiText.SYMBOL_CUT.format(count, SymbolScanner.MAX_SYMBOLS);
    }
    if (scan.result().skippedLines() > 0) {
      count = UiText.SYMBOL_SKIPPED.format(count, scan.result().skippedLines());
    }
    return count;
  }

  /**
   * Moves the caret to a row's symbol.
   *
   * @param index the row
   */
  void choose(int index) {
    if (index < 0 || index >= matched.size() || editor == null || editor.disposed()) {
      return;
    }
    EditorController shown = documents.show(editor.document());
    jump(shown == null ? editor : shown, matched.get(index));
  }

  /**
   * Scans an editor's document and moves the caret to the symbol that best matches a query; with no
   * match it shows a notice.
   *
   * @param target the editor, just opened or shown
   * @param text the symbol query; empty moves nothing
   */
  void jumpToBest(final EditorController target, final String text) {
    if (text.isEmpty()) {
      return;
    }
    final Document document = target.document();
    final long revision = document.revision();
    final int caret = target.selections().selections().primary().caret();
    Consumer<DocumentSymbols.Scan> done =
        new Consumer<DocumentSymbols.Scan>() {
          @Override
          public void accept(DocumentSymbols.Scan result) {
            if (target.disposed()
                || document.revision() != revision
                || target.selections().selections().primary().caret() != caret) {
              return;
            }
            SymbolScanner.Symbol best = best(result.result().symbols(), text);
            if (best == null) {
              view.showNotice(UiText.SYMBOL_NOT_FOUND.format(text, documents.nameOf(document)));
            } else {
              jump(target, best);
            }
          }
        };
    DocumentSymbols.Scan kept =
        documents.symbolCache().request(target, grammars, documents.pathOf(document), done);
    if (kept != null) {
      done.accept(kept);
    }
  }

  /** Returns the highest-scoring symbol, the first among equals, or null when none matches. */
  static SymbolScanner.Symbol best(List<SymbolScanner.Symbol> symbols, String text) {
    SymbolScanner.Symbol best = null;
    int top = -1;
    for (SymbolScanner.Symbol s : symbols) {
      int score = Fuzzy.score(text, s.name());
      if (score > top) {
        top = score;
        best = s;
      }
    }
    return best;
  }

  private static void jump(EditorController target, SymbolScanner.Symbol symbol) {
    TextSnapshot text = target.document().text();
    int line = Math.min(symbol.line(), text.lineCount() - 1);
    int column = Math.min(symbol.column(), text.lineLength(line));
    target.jumping();
    target.moveCaret(text.offsetOfLine(line) + column);
    if (target.view() != null) {
      target.view().focus();
    }
  }

  /**
   * Stops taking results; the popup closed. A running scan goes on into the cache, and a later
   * {@link #jumpToBest} still runs.
   */
  void close() {
    closed = true;
    running = null;
  }
}
