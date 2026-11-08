package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SymbolScanner;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Scans an editor's document for symbols on a {@code qcaret-cpu-} worker with {@link
 * SymbolScanner}: the document's grammar, or, while it has none, the grammar {@link
 * GrammarSet#detect} picks from the file name and first line. Lines over {@link
 * SyntaxEngine#DEFAULT_MAX_LINE_CHARS} are skipped and the scan stops at {@link
 * SymbolScanner#MAX_SYMBOLS}. The result reaches the UI thread unless its token was canceled; the
 * caller compares its revision with the document's.
 */
final class DocumentSymbols {
  /**
   * A finished scan.
   *
   * @param document the document scanned
   * @param revision the revision the scan read
   * @param grammar the grammar's name, or null for plain text
   * @param result the symbols
   * @param rules false when the grammar has no symbol rules
   */
  record Scan(
      Document document,
      long revision,
      String grammar,
      SymbolScanner.Result result,
      boolean rules) {}

  private static final SymbolScanner.Result NONE = new SymbolScanner.Result(List.of(), false, 0);

  private DocumentSymbols() {}

  /**
   * Starts a scan.
   *
   * @param workers runs it
   * @param ui receives the result
   * @param editor the editor whose document is scanned
   * @param grammars detects a grammar for a document without one, or null
   * @param path the document's file, or null
   * @param done takes the result on the UI thread
   * @return the token that cancels the scan, or null when the workers are closed
   */
  static CancellationToken start(
      Workers workers,
      final UiDispatcher ui,
      EditorController editor,
      GrammarLibrary grammars,
      Path path,
      final Consumer<Scan> done) {
    final Document document = editor.document();
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    final Syntax compiled = editor.compiledSyntax();
    final GrammarSet set = compiled != null || grammars == null ? null : grammars.set();
    Path name = path == null ? null : path.getFileName();
    final String fileName = name == null ? null : name.toString();
    final CancellationToken token = new CancellationToken();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final Scan scan;
              try {
                Syntax syntax = compiled;
                if (syntax == null && set != null) {
                  int length = Math.min(text.lineLength(0), GrammarSet.FIRST_LINE_CHARS);
                  String id = set.detect(fileName, text.subSequence(0, length), token);
                  syntax = GrammarSet.PLAIN.equals(id) ? null : set.syntax(id);
                }
                scan =
                    syntax == null
                        ? new Scan(document, revision, null, NONE, false)
                        : new Scan(
                            document,
                            revision,
                            syntax.grammar().name(),
                            new SymbolScanner(
                                    syntax,
                                    SyntaxEngine.DEFAULT_MAX_LINE_CHARS,
                                    SymbolScanner.MAX_SYMBOLS)
                                .scan(text, token),
                            !syntax.grammar().symbols().isEmpty());
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (!token.isCanceled()) {
                        done.accept(scan);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      return null;
    }
    return token;
  }
}
