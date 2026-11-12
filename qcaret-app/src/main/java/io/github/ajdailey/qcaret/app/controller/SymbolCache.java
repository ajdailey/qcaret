package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SymbolScan;
import io.github.ajdailey.qcaret.core.syntax.SymbolScanner;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A window's Go to Symbol scans, the last finished one kept per open document. A request at the
 * kept scan's revision and grammar returns it without a scan. Any other request starts a {@link
 * DocumentSymbols} scan that resumes the kept one past the text edited since its revision ({@link
 * SymbolScanner#rescan}), or scans from the first line when the grammar changed or nothing is kept.
 * A request for the grammar of the running scan joins it, and its callers compare the result's
 * revision; a request for another grammar cancels it and takes over its callers. Closing the list
 * does not cancel a scan, so the next request finds it kept.
 *
 * <p>The kept scan is charged to the documents' {@link MemoryBudget} at {@link SymbolScan#bytes};
 * one the budget cannot hold is delivered and not kept. {@link #documentClosed} cancels the
 * document's scan and releases its charge. Confined to the UI thread.
 */
final class SymbolCache {
  /** What a scan's symbols depend on besides the text, compared by identity. */
  private record Key(Syntax compiled, GrammarSet set, String fileName) {
    boolean same(Key other) {
      return compiled == other.compiled
          && set == other.set
          && (fileName == null ? other.fileName == null : fileName.equals(other.fileName));
    }
  }

  /** The key of an entry with no scan; read only beside a scan, which sets the key. */
  private static final Key NO_KEY = new Key(null, null, null);

  /** One document's kept and running scans and the text edited since each began. */
  private static final class Entry implements DocumentListener {
    private final Document document;
    private Key key = NO_KEY;
    private DocumentSymbols.Scan scan;
    private SymbolScan resumable;
    private long charged;
    private int keptPrefix = Integer.MAX_VALUE;
    private int keptSuffix = Integer.MAX_VALUE;
    private Key runningKey = NO_KEY;
    private CancellationToken running;
    private int runningPrefix = Integer.MAX_VALUE;
    private int runningSuffix = Integer.MAX_VALUE;
    private List<Consumer<DocumentSymbols.Scan>> waiting =
        new ArrayList<Consumer<DocumentSymbols.Scan>>();

    Entry(Document document) {
      this.document = document;
    }

    @Override
    public void textChanged(DocumentChange change) {
      List<TextEdit> edits = change.edits();
      if (edits.isEmpty()) {
        return;
      }
      long before = document.text().length();
      for (TextEdit e : edits) {
        before -= e.inserted().length() - e.removed().length();
      }
      TextEdit first = edits.get(0);
      TextEdit last = edits.get(edits.size() - 1);
      int prefix = first.offset();
      int suffix = (int) (before - last.offset() - last.removed().length());
      keptPrefix = Math.min(keptPrefix, prefix);
      keptSuffix = Math.min(keptSuffix, suffix);
      runningPrefix = Math.min(runningPrefix, prefix);
      runningSuffix = Math.min(runningSuffix, suffix);
    }

    @Override
    public void dirtyChanged(boolean dirty) {
      // Only the text matters.
    }
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final Map<Document, Entry> entries = new IdentityHashMap<Document, Entry>();

  SymbolCache(Workers workers, UiDispatcher ui, MemoryBudget budget) {
    this.workers = workers;
    this.ui = ui;
    this.budget = budget;
  }

  /**
   * Returns the editor's document's kept scan, or starts or joins a scan that calls {@code done}.
   *
   * @param editor the editor whose document is scanned
   * @param grammars detects a grammar for a document without one, or null
   * @param path the document's file, or null
   * @param done takes a scan's result on the UI thread when none is kept; not called when the
   *     workers are closed or the document closes first
   * @return the kept scan at the document's revision and grammar, or null
   */
  DocumentSymbols.Scan request(
      EditorController editor,
      GrammarLibrary grammars,
      Path path,
      Consumer<DocumentSymbols.Scan> done) {
    Document document = editor.document();
    Syntax compiled = editor.compiledSyntax();
    Path name = path == null ? null : path.getFileName();
    Key key =
        new Key(
            compiled,
            compiled != null || grammars == null ? null : grammars.set(),
            name == null ? null : name.toString());
    Entry entry = entries.get(document);
    if (entry == null) {
      entry = new Entry(document);
      entries.put(document, entry);
      document.addListener(entry);
    }
    if (entry.scan != null && entry.scan.revision() == document.revision() && entry.key.same(key)) {
      return entry.scan;
    }
    entry.waiting.add(done);
    if (entry.running != null) {
      if (entry.runningKey.same(key)) {
        return null;
      }
      entry.running.cancel();
    }
    DocumentSymbols.Resume resume = null;
    if (entry.resumable != null && entry.key.same(key)) {
      resume = new DocumentSymbols.Resume(entry.resumable, entry.keptPrefix, entry.keptSuffix);
    }
    entry.resumable = null;
    final Entry e = entry;
    final CancellationToken[] token = new CancellationToken[1];
    token[0] =
        DocumentSymbols.start(
            workers,
            ui,
            editor,
            grammars,
            path,
            resume,
            new Consumer<DocumentSymbols.Scan>() {
              @Override
              public void accept(DocumentSymbols.Scan scan) {
                finished(e, token[0], scan);
              }
            });
    e.running = token[0];
    e.runningKey = key;
    e.runningPrefix = Integer.MAX_VALUE;
    e.runningSuffix = Integer.MAX_VALUE;
    return null;
  }

  private void finished(Entry entry, CancellationToken token, DocumentSymbols.Scan scan) {
    if (entry.running != token || entries.get(entry.document) != entry) {
      return;
    }
    entry.running = null;
    budget.release(entry.charged);
    entry.charged = 0;
    entry.scan = null;
    entry.resumable = null;
    long bytes = scan.scan() == null ? 0 : scan.scan().bytes();
    if (budget.reserve(bytes)) {
      entry.charged = bytes;
      entry.scan = scan;
      entry.resumable = scan.scan();
      entry.key = entry.runningKey;
      entry.keptPrefix = entry.runningPrefix;
      entry.keptSuffix = entry.runningSuffix;
    }
    List<Consumer<DocumentSymbols.Scan>> callers = entry.waiting;
    entry.waiting = new ArrayList<Consumer<DocumentSymbols.Scan>>();
    for (Consumer<DocumentSymbols.Scan> c : callers) {
      c.accept(scan);
    }
  }

  /**
   * Returns the document's kept scan, whatever its revision.
   *
   * @param document the document
   * @return the scan, or null
   */
  DocumentSymbols.Scan kept(Document document) {
    Entry entry = entries.get(document);
    return entry == null ? null : entry.scan;
  }

  /**
   * Drops a closing document's scans, stops following its edits and releases the charge.
   *
   * @param document the document
   */
  void documentClosed(Document document) {
    Entry entry = entries.remove(document);
    if (entry == null) {
      return;
    }
    document.removeListener(entry);
    if (entry.running != null) {
      entry.running.cancel();
    }
    budget.release(entry.charged);
  }
}
