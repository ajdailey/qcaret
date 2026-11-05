package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.Fuzzy;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.QuickOpenRow;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick Open and Switch Document: the open documents that match the query, in tab order among
 * equals, then (Quick Open only) the workspace index's files, at most {@value #MAX_ROWS} rows in
 * all. Each candidate is scored with {@link Fuzzy} on its file name; one whose name does not match
 * is scored on its path below the workspace folder and ranks after every name match. Equal scores
 * put the shorter path first, then the path in code-unit order. An empty query lists the open
 * documents in tab order, then (Quick Open only) the Recent Files not open, newest first; both hold
 * real paths, so a file is listed once. A row's detail is the path below the workspace folder, the
 * whole path outside it, or empty for an untitled document. A query ending in {@code :line} or
 * {@code :line:column} (from 1) matches the text before it and moves the caret there on open.
 * Confined to the UI thread.
 *
 * <p>An index of up to {@value #SYNC_FILES} files is matched on the UI thread; a larger one on a
 * {@code qcaret-cpu-} worker, canceled by the next query or the popup's close, and the rows change
 * when it ends. A file open in a tab is listed once, as the document. When an index build ends
 * while the popup shows, the query is matched again.
 */
final class QuickOpen implements QuickOpenInput {
  /** Most rows listed. */
  static final int MAX_ROWS = 100;

  /** Largest index matched on the UI thread. */
  static final int SYNC_FILES = 2_000;

  private static final Pattern POSITION = Pattern.compile("(.*?):(\\d{1,9})(?::(\\d{1,9}))?");

  /**
   * A parsed query.
   *
   * @param text the text matched, with {@code \} as {@code /}
   * @param line the line from 1, or 0 when none was given
   * @param column the column from 1, or 0 when none was given
   */
  record Query(String text, int line, int column) {
    static Query parse(String input) {
      String query = input.strip().replace('\\', '/');
      Matcher m = POSITION.matcher(query);
      if (m.matches() && !m.group(1).isEmpty()) {
        int column = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new Query(m.group(1), Integer.parseInt(m.group(2)), column);
      }
      return new Query(query, 0, 0);
    }
  }

  /**
   * The best files of an index for a query.
   *
   * @param files index positions, best first
   * @param matched every file that matched
   */
  record Ranked(int[] files, int matched) {}

  private final DocumentController documents;
  private final WorkspaceController workspace;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final boolean documentsOnly;
  private final List<Path> recent;
  private final Runnable indexChanged =
      new Runnable() {
        @Override
        public void run() {
          filter(input);
          view.quickOpenChanged();
        }
      };
  private String input = "";
  private Query query = new Query("", 0, 0);
  private List<QuickOpenRow> documentRows = List.of();
  private List<Document> documentTargets = List.of();
  private List<QuickOpenRow> fileRows = List.of();
  private List<Path> fileTargets = List.of();
  private int filesMatched;
  private FileIndex matchedIndex;
  private CancellationToken running;
  private boolean closed;

  QuickOpen(
      DocumentController documents,
      WorkspaceController workspace,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      boolean documentsOnly,
      List<Path> recent) {
    this.documents = documents;
    this.workspace = workspace;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.documentsOnly = documentsOnly;
    this.recent = documentsOnly ? List.<Path>of() : List.copyOf(recent);
    if (!documentsOnly) {
      workspace.addIndexListener(indexChanged);
    }
    filter("");
  }

  @Override
  public String title() {
    return documentsOnly ? UiText.SWITCH_DOCUMENT_TITLE.text() : UiText.QUICK_OPEN_TITLE.text();
  }

  @Override
  public void filter(String text) {
    if (closed) {
      return;
    }
    input = text;
    query = Query.parse(text);
    cancel();
    matchDocuments();
    fileRows = List.of();
    fileTargets = List.of();
    filesMatched = 0;
    matchedIndex = null;
    if (query.text().isEmpty()) {
      showRecent();
      return;
    }
    FileIndex index = documentsOnly ? null : workspace.index();
    if (index == null || query.text().isEmpty()) {
      return;
    }
    Set<String> open = openBelow(index.root());
    if (index.size() <= SYNC_FILES) {
      showFiles(index, rank(query.text(), index, open, MAX_ROWS, new CancellationToken()));
    } else {
      matchOnWorker(index, query.text(), open);
    }
  }

  private void matchOnWorker(final FileIndex index, final String text, final Set<String> open) {
    final CancellationToken token = new CancellationToken();
    running = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Ranked ranked = null;
              try {
                ranked = rank(text, index, open, MAX_ROWS, token);
              } catch (CancellationException e) {
                ranked = null;
              } finally {
                final Ranked result = ranked;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        matched(token, index, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  private void matched(CancellationToken token, FileIndex index, Ranked ranked) {
    if (closed || token != running) {
      return;
    }
    running = null;
    if (ranked != null) {
      showFiles(index, ranked);
      view.quickOpenChanged();
    }
  }

  private void showFiles(FileIndex index, Ranked ranked) {
    int room = Math.max(0, MAX_ROWS - documentRows.size());
    int count = Math.min(room, ranked.files().length);
    List<QuickOpenRow> list = new ArrayList<QuickOpenRow>(count);
    List<Path> paths = new ArrayList<Path>(count);
    for (int i = 0; i < count; i++) {
      int file = ranked.files()[i];
      String path = index.path(file);
      list.add(new QuickOpenRow(path.substring(path.lastIndexOf('/') + 1), path, false));
      paths.add(index.resolve(file));
    }
    fileRows = list;
    fileTargets = paths;
    filesMatched = ranked.matched();
    matchedIndex = index;
  }

  /** Lists the Recent Files that are not open, after the open documents. */
  private void showRecent() {
    Set<Path> listed = new HashSet<Path>();
    for (Document document : documents.openDocuments()) {
      Path path = documents.pathOf(document);
      if (path != null) {
        listed.add(path);
      }
    }
    Path root = workspace.root();
    int room = Math.max(0, MAX_ROWS - documentRows.size());
    List<QuickOpenRow> list = new ArrayList<QuickOpenRow>();
    List<Path> paths = new ArrayList<Path>();
    for (Path path : recent) {
      if (paths.size() == room) {
        break;
      }
      if (listed.add(path)) {
        Path name = path.getFileName();
        list.add(
            new QuickOpenRow(
                name == null ? path.toString() : name.toString(), below(root, path), false));
        paths.add(path);
      }
    }
    fileRows = list;
    fileTargets = paths;
  }

  /** Lists the open documents matching the query, by tier, then score, then tab order. */
  private void matchDocuments() {
    Path root = workspace.root();
    List<Document> open = documents.openDocuments();
    List<int[]> keys = new ArrayList<int[]>();
    for (int i = 0; i < open.size(); i++) {
      Document document = open.get(i);
      String name = documents.nameOf(document);
      int tier = 0;
      int score = Fuzzy.score(query.text(), name);
      Path path = documents.pathOf(document);
      if (score < 0 && path != null) {
        tier = 1;
        score = Fuzzy.score(query.text(), below(root, path));
      }
      if (score >= 0) {
        keys.add(new int[] {tier, score, i});
      }
    }
    keys.sort(
        new Comparator<int[]>() {
          @Override
          public int compare(int[] a, int[] b) {
            if (a[0] != b[0]) {
              return Integer.compare(a[0], b[0]);
            }
            if (a[1] != b[1]) {
              return Integer.compare(b[1], a[1]);
            }
            return Integer.compare(a[2], b[2]);
          }
        });
    int count = Math.min(MAX_ROWS, keys.size());
    List<QuickOpenRow> list = new ArrayList<QuickOpenRow>(count);
    List<Document> targets = new ArrayList<Document>(count);
    for (int i = 0; i < count; i++) {
      Document document = open.get(keys.get(i)[2]);
      Path path = documents.pathOf(document);
      String name = documents.nameOf(document);
      list.add(new QuickOpenRow(name, path == null ? "" : below(root, path), true));
      targets.add(document);
    }
    documentRows = list;
    documentTargets = targets;
  }

  /** Returns the relative paths of open documents below a folder, joined with {@code /}. */
  private Set<String> openBelow(Path root) {
    Set<String> open = new HashSet<String>();
    for (Document document : documents.openDocuments()) {
      Path path = documents.pathOf(document);
      if (path != null && path.startsWith(root)) {
        open.add(below(root, path));
      }
    }
    return open;
  }

  /** Returns a path below a folder joined with {@code /}, or the whole path outside it. */
  static String below(Path root, Path path) {
    if (root == null || !path.startsWith(root)) {
      return path.toString();
    }
    StringBuilder text = new StringBuilder();
    for (Path element : root.relativize(path)) {
      if (text.length() > 0) {
        text.append('/');
      }
      text.append(element);
    }
    return text.toString();
  }

  /**
   * Ranks an index's files for a query, keeping the best {@code max}.
   *
   * @param text the query text
   * @param index the files
   * @param skip relative paths left out
   * @param max most files returned
   * @param token checked every 1,024 files
   * @return the best files and the match count
   */
  static Ranked rank(
      String text, final FileIndex index, Set<String> skip, int max, CancellationToken token) {
    final Comparator<int[]> better =
        new Comparator<int[]>() {
          @Override
          public int compare(int[] a, int[] b) {
            if (a[0] != b[0]) {
              return Integer.compare(a[0], b[0]);
            }
            if (a[1] != b[1]) {
              return Integer.compare(b[1], a[1]);
            }
            String pa = index.path(a[2]);
            String pb = index.path(b[2]);
            if (pa.length() != pb.length()) {
              return Integer.compare(pa.length(), pb.length());
            }
            return pa.compareTo(pb);
          }
        };
    PriorityQueue<int[]> best = new PriorityQueue<int[]>(Math.max(1, max), better.reversed());
    int matched = 0;
    for (int i = 0; i < index.size(); i++) {
      if ((i & 1023) == 0) {
        token.check();
      }
      String path = index.path(i);
      if (skip.contains(path)) {
        continue;
      }
      int tier = 0;
      int score = Fuzzy.score(text, path.substring(path.lastIndexOf('/') + 1));
      if (score < 0) {
        tier = 1;
        score = Fuzzy.score(text, path);
      }
      if (score < 0) {
        continue;
      }
      matched++;
      int[] candidate = {tier, score, i};
      if (best.size() < max) {
        best.add(candidate);
      } else if (max > 0 && better.compare(candidate, best.peek()) < 0) {
        best.poll();
        best.add(candidate);
      }
    }
    int[] files = new int[best.size()];
    for (int i = files.length - 1; i >= 0; i--) {
      files[i] = best.poll()[2];
    }
    return new Ranked(files, matched);
  }

  @Override
  public List<QuickOpenRow> rows() {
    List<QuickOpenRow> all = new ArrayList<QuickOpenRow>(documentRows.size() + fileRows.size());
    all.addAll(documentRows);
    all.addAll(fileRows);
    return all;
  }

  @Override
  public String summary() {
    if (documentsOnly) {
      return UiText.QUICK_OPEN_DOCUMENTS.format(documentRows.size(), documents.count());
    }
    Path root = workspace.root();
    FileIndex index = workspace.index();
    if (root == null) {
      return UiText.QUICK_OPEN_NO_WORKSPACE.text();
    }
    if (index == null) {
      return UiText.QUICK_OPEN_INDEXING.text();
    }
    if (running != null) {
      return UiText.QUICK_OPEN_MATCHING.format(index.size());
    }
    int matched = matchedIndex == null ? 0 : filesMatched;
    String files =
        query.text().isEmpty()
            ? UiText.QUICK_OPEN_INDEXED.format(index.size())
            : UiText.QUICK_OPEN_FILES.format(matched, index.size());
    return switch (index.cut()) {
      case NONE -> files;
      case LIMIT -> UiText.QUICK_OPEN_CUT_LIMIT.format(files, FileIndex.MAX_FILES);
      case MEMORY -> UiText.QUICK_OPEN_CUT_MEMORY.format(files);
    };
  }

  @Override
  public void choose(int index) {
    if (index < 0) {
      return;
    }
    final Query chosen = query;
    if (index < documentTargets.size()) {
      EditorController editor = documents.show(documentTargets.get(index));
      if (editor != null) {
        arrive(editor, chosen);
      }
      return;
    }
    int file = index - documentTargets.size();
    if (file < fileTargets.size()) {
      documents.open(
          fileTargets.get(file),
          new Consumer<EditorController>() {
            @Override
            public void accept(EditorController editor) {
              arrive(editor, chosen);
            }
          });
    }
  }

  private static void arrive(EditorController editor, Query chosen) {
    if (chosen.line() > 0) {
      editor.goTo(chosen.line() - 1, Math.max(0, chosen.column() - 1));
    }
    if (editor.view() != null) {
      editor.view().focus();
    }
  }

  @Override
  public void close() {
    closed = true;
    cancel();
    workspace.removeIndexListener(indexChanged);
  }

  private void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }
}
