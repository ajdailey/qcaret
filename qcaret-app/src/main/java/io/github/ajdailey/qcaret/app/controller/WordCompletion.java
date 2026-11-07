package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.edit.WordIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Word completion over the open documents of a window. Each request runs on a {@code qcaret-cpu-}
 * worker: a document's {@link WordIndex} is built again when its revision moved since the last
 * build, else reused. A newer request cancels the running one. Confined to the UI thread; the
 * indexes are kept per open document and dropped when it closes.
 */
final class WordCompletion {
  /** Words a request returns at most. */
  static final int MAX_WORDS = 200;

  private final Workers workers;
  private final UiDispatcher ui;
  private final Supplier<List<Document>> documents;
  private final Map<Document, Indexed> indexes = new IdentityHashMap<Document, Indexed>();
  private CancellationToken running;

  /** A document's index and the revision it read. */
  private record Indexed(long revision, WordIndex index) {}

  /** A document's text for a worker, with its cached index when that is current. */
  private record Source(Document document, TextSnapshot text, long revision, WordIndex index) {}

  /**
   * Creates the completion.
   *
   * @param workers runs the lookups
   * @param ui receives the results
   * @param documents the open documents
   */
  WordCompletion(Workers workers, UiDispatcher ui, Supplier<List<Document>> documents) {
    this.workers = workers;
    this.ui = ui;
    this.documents = documents;
  }

  /**
   * Looks up the words that complete a prefix, ignoring case: the current document's first, then
   * the rest; words that start with the prefix as typed come before the others.
   *
   * @param current the document the prefix is typed in
   * @param prefix the prefix, not empty
   * @param done receives the words on the UI thread, unless a newer request or {@link #cancel} came
   *     first
   */
  void request(Document current, final String prefix, final Consumer<List<String>> done) {
    cancel();
    final List<Source> sources = new ArrayList<Source>();
    List<Document> open = new ArrayList<Document>(documents.get());
    open.remove(current);
    open.add(0, current);
    for (Document d : open) {
      Indexed cached = indexes.get(d);
      long revision = d.revision();
      WordIndex index = cached != null && cached.revision() == revision ? cached.index() : null;
      sources.add(new Source(d, index == null ? d.text() : null, revision, index));
    }
    final CancellationToken token = new CancellationToken();
    running = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final List<Indexed> built = new ArrayList<Indexed>(sources.size());
              final List<String> words;
              try {
                words = lookUp(sources, prefix, built, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (running != token) {
                        return;
                      }
                      running = null;
                      keep(sources, built);
                      done.accept(words);
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  /** Cancels the running request; its result is dropped. */
  void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  /** Drops a document's index, which holds its text; the document left this window. */
  void documentClosed(Document document) {
    indexes.remove(document);
  }

  /** Keeps the indexes of the documents still open. */
  private void keep(List<Source> sources, List<Indexed> built) {
    List<Document> open = documents.get();
    indexes.keySet().retainAll(open);
    for (int i = 0; i < sources.size(); i++) {
      Document d = sources.get(i).document();
      if (open.contains(d)) {
        indexes.put(d, built.get(i));
      }
    }
  }

  /** Builds the missing indexes into {@code built}, one per source, and merges the matches. */
  @WorkerThread
  static List<String> lookUp(
      List<Source> sources, final String prefix, List<Indexed> built, CancellationToken token) {
    Set<String> found = new LinkedHashSet<String>();
    for (Source source : sources) {
      WordIndex index = source.index();
      if (index == null) {
        index = WordIndex.build(source.text(), token);
      }
      built.add(new Indexed(source.revision(), index));
      found.addAll(index.complete(prefix, MAX_WORDS));
    }
    List<String> words = new ArrayList<String>(found);
    words.sort(
        new Comparator<String>() {
          @Override
          public int compare(String a, String b) {
            return Boolean.compare(!a.startsWith(prefix), !b.startsWith(prefix));
          }
        });
    return words.size() > MAX_WORDS ? new ArrayList<String>(words.subList(0, MAX_WORDS)) : words;
  }
}
