package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The grammar of each document of one {@link DocumentController}: its {@link SyntaxHighlighter},
 * detection on a {@code qcaret-cpu-} worker, the user's choice and the remembered one. A detection
 * result is dropped when the document closed, the user chose a grammar meanwhile or the grammars
 * were read again. Confined to the UI thread.
 */
final class DocumentGrammars {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentGrammars.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final List<OpenDocument> documents;
  private GrammarLibrary library;
  private Consumer<GrammarSet> listener;
  private Consumer<String> chosen;
  private int maxLineChars = SyntaxEngine.DEFAULT_MAX_LINE_CHARS;

  DocumentGrammars(Workers workers, UiDispatcher ui, MainView view, List<OpenDocument> documents) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.documents = documents;
  }

  /** {@link DocumentController#setGrammars}. */
  void setLibrary(GrammarLibrary value) {
    library = value;
    listener =
        new Consumer<GrammarSet>() {
          @Override
          public void accept(GrammarSet set) {
            for (OpenDocument d : documents) {
              if (d.manualGrammar && set.get(d.grammar) != null) {
                apply(d, d.grammar, set);
              } else {
                d.manualGrammar = false;
                detect(d);
              }
            }
          }
        };
    value.addListener(listener);
  }

  /** Stops following the grammar library. */
  void close() {
    if (library != null) {
      library.removeListener(listener);
    }
  }

  /** Sets what receives a grammar's id when the user chooses it with {@link #choose}. */
  void setChosen(Consumer<String> value) {
    chosen = value;
  }

  /** {@link DocumentController#setSyntax} for a document. */
  void choose(OpenDocument d, String id) {
    GrammarSet set = current();
    if (set != null && set.get(id) != null) {
      d.manualGrammar = true;
      apply(d, id, set);
      if (chosen != null) {
        chosen.accept(id);
      }
    }
  }

  /** Returns a document's grammar, Plain Text included; null before the read. */
  Grammar grammarOf(OpenDocument d) {
    GrammarSet set = current();
    return set == null ? null : set.get(d.grammar);
  }

  /** Returns the grammars, plain text first, then by name; empty before they are read. */
  List<Grammar> choices() {
    GrammarSet set = current();
    return set == null ? List.of() : set.byName();
  }

  /** Returns the longest line colored. */
  int maxLineChars() {
    return maxLineChars;
  }

  /** {@link DocumentController#setMaxHighlightLineChars}. */
  void setMaxLineChars(int chars) {
    maxLineChars = chars;
    for (OpenDocument d : documents) {
      d.syntax.setMaxLineChars(chars);
    }
  }

  /** Creates a document's highlighter, which redraws every view of the document. */
  SyntaxHighlighter highlighter(final OpenDocument d) {
    return new SyntaxHighlighter(
        d.document,
        workers,
        ui,
        new Runnable() {
          @Override
          public void run() {
            for (EditorController e : d.editors) {
              if (e.view() != null) {
                e.view().redrawHighlights();
              }
            }
          }
        },
        maxLineChars);
  }

  /** Picks the document's grammar on a worker from its file name and first line. */
  void detect(final OpenDocument d) {
    final GrammarSet set = current();
    if (set == null || d.manualGrammar || d.closed) {
      return;
    }
    Path file = d.file.path == null ? null : d.file.path.getFileName();
    final String name = file == null ? null : file.toString();
    final TextSnapshot text = d.document.text();
    final CancellationToken token = new CancellationToken();
    d.detecting++;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              int length = Math.min(text.lineLength(0), GrammarSet.FIRST_LINE_CHARS);
              final String id = set.detect(name, text.subSequence(0, length), token);
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      d.detecting--;
                      if (!d.closed && !d.manualGrammar && current() == set) {
                        apply(d, id, set);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      d.detecting--;
      LOG.debug("Syntax not detected: workers are closed");
    }
  }

  /** Uses a remembered grammar; a grammar the library lacks falls back to detection. */
  void restore(OpenDocument d, String id) {
    if (id.isEmpty()) {
      return;
    }
    GrammarSet set = current();
    d.manualGrammar = true;
    d.grammar = id;
    if (set != null && set.get(id) != null) {
      apply(d, id, set);
    } else if (set != null) {
      d.manualGrammar = false;
      d.grammar = GrammarSet.PLAIN;
      detect(d);
    }
  }

  /** Returns the grammar a document's editors get: null for plain text and before the read. */
  Grammar editorGrammar(OpenDocument d) {
    GrammarSet set = current();
    return set == null || GrammarSet.PLAIN.equals(d.grammar) ? null : set.get(d.grammar);
  }

  private void apply(OpenDocument d, String id, GrammarSet set) {
    d.grammar = id;
    d.syntax.setSyntax(GrammarSet.PLAIN.equals(id) ? null : set.syntax(id));
    for (EditorController e : d.editors) {
      e.setGrammar(editorGrammar(d));
    }
    view.documentStateChanged();
  }

  private GrammarSet current() {
    return library == null ? null : library.set();
  }
}
