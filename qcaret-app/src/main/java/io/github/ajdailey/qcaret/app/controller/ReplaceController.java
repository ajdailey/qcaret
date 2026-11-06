package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.search.ReplacePreview;
import io.github.ajdailey.qcaret.app.search.ReplaceRun;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.PreviewLine;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.FileReplace;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.search.ReplaceTemplate;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Replace across a HyperSearch result set: the preview, the runs with their reports, and Restore
 * Replace Run. Confined to the UI thread; reads, diffs and writes run on workers.
 *
 * <ul>
 *   <li>The preview lists a finished set's groups with matches, every file and match checked. The
 *       selected file's before and after view ({@link ReplacePreview}) is computed on a worker from
 *       the open document's text or the file's bytes, again after each change of the replacement or
 *       of its checks; a file that Apply would skip shows why.
 *   <li>Apply runs a {@link ReplaceRun} over the checked matches and shows its report. An open
 *       document is replaced in only at the revision searched (a file group: while its text has the
 *       SHA-256 searched), as one undo step, and stays dirty; a file not open is written through
 *       {@link FileReplace} with a backup. One run or restore at a time.
 *   <li>When the run ends, each file group it changed is marked stale; Rerun File searches it
 *       again.
 *   <li>Restore Replace Run lists the runs kept under {@code <settings>/replace-backups} and writes
 *       one back ({@link ReplaceRun#restore}); a file changed since the run is refused. An open
 *       document whose file was restored is checked at once, so a clean one reloads and a dirty one
 *       asks.
 * </ul>
 */
public final class ReplaceController implements ReplaceInput {
  private static final IntFunction<String> MORE =
      new IntFunction<String>() {
        @Override
        public String apply(int changes) {
          return UiText.REPLACE_MORE.format(changes);
        }
      };

  private static final DateTimeFormatter RUN_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private HyperSearch.Limits limits;
  private final Path backupRoot;
  private int backupDays;
  private final List<Report> reports = new ArrayList<Report>();
  private final List<PlanFile> files = new ArrayList<PlanFile>();
  private String replacement = "";
  private ResultSet set;
  private TextMatcher matcher;
  private ReplaceTemplate template;
  private String problem;
  private int selected = -1;
  private List<PreviewLine> preview = List.of();
  private CancellationToken previewing;
  private long generation;
  private boolean disposed;

  /** One group of the preview and its checks. */
  private static final class PlanFile {
    final FileResult group;
    final boolean[] unchecked;
    boolean checked = true;
    int count;

    PlanFile(FileResult group) {
      this.group = group;
      this.unchecked = new boolean[group.matchCount()];
      this.count = group.matchCount();
    }

    int[] spans() {
      int[] spans = new int[2 * count];
      int n = 0;
      for (int i = 0; i < unchecked.length; i++) {
        if (!unchecked[i]) {
          spans[n++] = group.start(i);
          spans[n++] = group.end(i);
        }
      }
      return spans;
    }
  }

  /**
   * Creates the controller.
   *
   * @param documents the window's documents
   * @param workers runs previews, replaces and restores
   * @param ui receives worker results
   * @param view the window
   * @param budget charged while a file is replaced
   * @param limits the largest file read
   * @param backupRoot {@code <settings>/replace-backups}
   * @param backupDays the days a run's backups are kept
   */
  public ReplaceController(
      DocumentController documents,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget,
      HyperSearch.Limits limits,
      Path backupRoot,
      int backupDays) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
    this.limits = limits;
    this.backupRoot = backupRoot;
    this.backupDays = backupDays;
  }

  // Preview.

  /**
   * Opens the preview for a finished set, replacing an open preview.
   *
   * @param results the set
   */
  void preview(ResultSet results) {
    if (disposed) {
      return;
    }
    closed();
    set = results;
    matcher = results.request().query().compile();
    for (FileResult f : results.files()) {
      if (f.matchCount() > 0) {
        files.add(new PlanFile(f));
      }
    }
    compileTemplate();
    view.showReplacePreview(this);
    select(files.isEmpty() ? -1 : 0);
  }

  /**
   * Reports whether the preview shows.
   *
   * @return true while open
   */
  boolean previewOpen() {
    return set != null;
  }

  private void compileTemplate() {
    try {
      template = matcher.template(replacement);
      problem = null;
    } catch (IllegalArgumentException e) {
      template = null;
      problem = e.getMessage();
    }
  }

  @Override
  public String title() {
    SearchRequest q = set.request();
    String scope = q.scope().label() + (q.folder() == null ? "" : " " + q.folder());
    return UiText.REPLACE_HEADER.format(q.text(), scope, set.matches(), set.files().size());
  }

  @Override
  public String replacement() {
    return replacement;
  }

  @Override
  public void setReplacement(String text) {
    if (set == null || text.equals(replacement)) {
      return;
    }
    replacement = text;
    compileTemplate();
    computePreview();
  }

  @Override
  public int fileCount() {
    return files.size();
  }

  @Override
  public String file(int file) {
    PlanFile p = files.get(file);
    Path parent = p.group.path() == null ? null : p.group.path().getParent();
    String text =
        UiText.REPLACE_FILE.format(
            p.group.name(),
            parent == null ? "" : parent.toString(),
            p.checked ? p.count : 0,
            p.group.matchCount());
    return p.group.stale() ? text + "; " + UiText.SEARCH_CHANGED.text() : text;
  }

  @Override
  public boolean fileChecked(int file) {
    return files.get(file).checked;
  }

  @Override
  public void setFileChecked(int file, boolean checked) {
    PlanFile p = files.get(file);
    if (p.checked != checked) {
      p.checked = checked;
      changed(file);
    }
  }

  @Override
  public int matchCount(int file) {
    return files.get(file).group.matchCount();
  }

  @Override
  public ResultLabel match(int file, int match) {
    FileResult f = files.get(file).group;
    TextSearch.Hit hit = f.hit(match);
    String prefix = (f.line(match) + 1) + ": ";
    return new ResultLabel(
        prefix + hit.snippet(),
        prefix.length() + hit.snippetStart(),
        prefix.length() + hit.snippetEnd(),
        f.stale(match));
  }

  @Override
  public boolean matchChecked(int file, int match) {
    return !files.get(file).unchecked[match];
  }

  @Override
  public void setMatchChecked(int file, int match, boolean checked) {
    PlanFile p = files.get(file);
    if (p.unchecked[match] == checked) {
      p.unchecked[match] = !checked;
      p.count += checked ? 1 : -1;
      changed(file);
    }
  }

  private void changed(int file) {
    if (file == selected) {
      computePreview();
    } else {
      view.replacePreviewChanged();
    }
  }

  @Override
  public void select(int file) {
    selected = file;
    computePreview();
  }

  @Override
  public List<PreviewLine> preview() {
    return preview;
  }

  @Override
  public String summary() {
    if (problem != null) {
      return UiText.REPLACE_BAD_TEMPLATE.format(problem);
    }
    int matches = 0;
    int checkedFiles = 0;
    int total = 0;
    for (PlanFile p : files) {
      total += p.group.matchCount();
      if (p.checked && p.count > 0) {
        matches += p.count;
        checkedFiles++;
      }
    }
    if (matches == 0) {
      return UiText.REPLACE_NOTHING_CHECKED.text();
    }
    return UiText.REPLACE_SUMMARY.format(matches, total, checkedFiles, files.size());
  }

  @Override
  public boolean canApply() {
    if (template == null) {
      return false;
    }
    for (PlanFile p : files) {
      if (p.checked && p.count > 0) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void closed() {
    if (previewing != null) {
      previewing.cancel();
      previewing = null;
    }
    generation++;
    set = null;
    files.clear();
    selected = -1;
    preview = List.of();
  }

  /** Returns the open document a group's matches are replaced in, or null. */
  private Document openDocument(FileResult group) {
    Document d = group.document();
    if (d != null) {
      return documents.holderOf(d) == null ? null : d;
    }
    return group.path() == null ? null : documents.documentAnywhere(group.path());
  }

  /** Computes the selected file's before and after view on a worker. */
  private void computePreview() {
    if (previewing != null) {
      previewing.cancel();
      previewing = null;
    }
    final long current = ++generation;
    preview = null;
    view.replacePreviewChanged();
    if (selected < 0 || template == null) {
      preview = List.of();
      view.replacePreviewChanged();
      return;
    }
    PlanFile p = files.get(selected);
    int[] spans = p.checked ? p.spans() : new int[0];
    if (spans.length == 0) {
      preview = List.of();
      view.replacePreviewChanged();
      return;
    }
    FileResult group = p.group;
    Document document = openDocument(group);
    CancellationToken token = new CancellationToken();
    previewing = token;
    if (document != null) {
      if (group.document() != null && document.revision() != group.searchedRevision()) {
        showPreview(current, note(ReplaceRun.Reason.CHANGED));
        return;
      }
      previewDocument(
          current,
          document.text(),
          document.lineEnding(),
          group.encoding(),
          group.document() == null ? group.digest() : null,
          spans,
          token);
    } else if (group.digest() != null && group.path() != null) {
      previewFile(current, group.path(), group.digest(), group.encoding(), spans, token);
    } else {
      showPreview(current, note(ReplaceRun.Reason.CLOSED));
    }
  }

  private void previewDocument(
      final long current,
      final TextSnapshot text,
      final LineEnding ending,
      final TextEncoding encoding,
      final byte[] digest,
      final int[] spans,
      final CancellationToken token) {
    final TextMatcher m = matcher;
    final ReplaceTemplate t = template;
    final IntFunction<String> more = MORE;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              List<PreviewLine> lines;
              try {
                if (digest != null && !FileReplace.holds(text, encoding, digest)) {
                  lines = note(ReplaceRun.Reason.CHANGED);
                } else {
                  lines = previewLines(text, spans, m, t, ending, encoding, more, token);
                }
              } catch (CancellationException e) {
                return;
              }
              showPreviewLater(current, lines);
            }
          });
    } catch (RejectedExecutionException e) {
      previewing = null;
    }
  }

  private void previewFile(
      final long current,
      final Path path,
      final byte[] digest,
      final TextEncoding encoding,
      final int[] spans,
      final CancellationToken token) {
    final TextMatcher m = matcher;
    final ReplaceTemplate t = template;
    final IntFunction<String> more = MORE;
    final long maxBytes = limits.maxFileBytes();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                List<PreviewLine> lines;
                try {
                  lines = readPreview(path, digest, encoding, maxBytes, spans, m, t, more, token);
                } catch (CancellationException e) {
                  return;
                }
                showPreviewLater(current, lines);
              }
            });
    if (!started) {
      previewing = null;
    }
  }

  @WorkerThread
  private static List<PreviewLine> readPreview(
      Path path,
      byte[] digest,
      TextEncoding encoding,
      long maxBytes,
      int[] spans,
      TextMatcher m,
      ReplaceTemplate t,
      IntFunction<String> more,
      CancellationToken token) {
    FileReplace.Loaded loaded = FileReplace.load(path, digest, encoding, maxBytes);
    if (loaded.skip() != null) {
      return note(ReplaceRun.Reason.valueOf(loaded.skip().name()));
    }
    String text = loaded.text();
    LineEnding ending = LineEndingStats.count(text, 0, text.length()).dominant(LineEnding.LF);
    return previewLines(text, spans, m, t, ending, encoding, more, token);
  }

  @WorkerThread
  private static List<PreviewLine> previewLines(
      CharSequence text,
      int[] spans,
      TextMatcher m,
      ReplaceTemplate t,
      LineEnding ending,
      TextEncoding encoding,
      IntFunction<String> more,
      CancellationToken token) {
    try {
      List<Replacement> replacements = FileReplace.replacements(text, spans, m, t, ending, token);
      if (replacements.isEmpty()) {
        return note(ReplaceRun.Reason.NO_MATCHES);
      }
      if (!FileReplace.encodable(replacements, encoding)) {
        return note(ReplaceRun.Reason.UNMAPPABLE);
      }
      return ReplacePreview.lines(text, replacements, more, token);
    } catch (PatternTooComplexException e) {
      return note(ReplaceRun.Reason.TOO_COMPLEX);
    }
  }

  private static List<PreviewLine> note(ReplaceRun.Reason reason) {
    return List.of(
        new PreviewLine(
            PreviewLine.Kind.NOTE, 0, UiText.REPLACE_PREVIEW_SKIPPED.format(reasonText(reason))));
  }

  private void showPreviewLater(final long current, final List<PreviewLine> lines) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            showPreview(current, lines);
          }
        });
  }

  private void showPreview(long current, List<PreviewLine> lines) {
    if (current != generation || disposed || set == null) {
      return;
    }
    previewing = null;
    preview = lines;
    view.replacePreviewChanged();
  }

  // Apply.

  /**
   * Starts the replace of the checked matches and shows its report; refused with a notice while a
   * replace or restore runs.
   *
   * @return true when the run started and the preview closes
   */
  @Override
  public boolean apply() {
    if (set == null || !canApply()) {
      return false;
    }
    if (running()) {
      view.showNotice(UiText.REPLACE_RUNNING.text());
      return false;
    }
    List<ReplaceRun.Item> items = new ArrayList<ReplaceRun.Item>();
    final List<FileResult> groups = new ArrayList<FileResult>();
    for (PlanFile p : files) {
      if (p.checked && p.count > 0) {
        items.add(item(p));
        groups.add(p.group);
      }
    }
    final Report report =
        new Report(UiText.REPLACE_REPORT_TITLE.format(set.request().text(), replacement), groups);
    final TextMatcher m = matcher;
    final ReplaceTemplate t = template;
    closed();
    reports.add(report);
    view.showReplaceReport(report);
    report.start(
        ReplaceRun.replace(
            items,
            m,
            t,
            backupRoot,
            backupDays,
            limits,
            workers,
            ui,
            budget,
            new ReplaceRun.Buffers() {
              @Override
              public ReplaceRun.Row apply(ReplaceRun.Item item, List<Replacement> replacements) {
                return applyToDocument(item, replacements);
              }
            },
            report));
    return true;
  }

  /** Describes a checked group for the run; an open document is read here. */
  private ReplaceRun.Item item(PlanFile p) {
    FileResult group = p.group;
    String name = group.name();
    Path path = group.path();
    int[] spans = p.spans();
    Document d = openDocument(group);
    if (d != null) {
      ReplaceRun.Reason unavailable = null;
      if (group.document() != null && d.revision() != group.searchedRevision()) {
        unavailable = ReplaceRun.Reason.CHANGED;
      } else if (d.isReadOnly() || documents.holderOf(d).binary(d)) {
        unavailable = ReplaceRun.Reason.READ_ONLY;
      }
      return new ReplaceRun.Item(
          name,
          path,
          d,
          d.text(),
          d.revision(),
          d.lineEnding(),
          group.encoding(),
          group.document() == null ? group.digest() : null,
          spans,
          unavailable);
    }
    if (group.digest() == null || path == null) {
      return new ReplaceRun.Item(
          name,
          path,
          null,
          null,
          -1,
          null,
          group.encoding(),
          null,
          spans,
          ReplaceRun.Reason.CLOSED);
    }
    return new ReplaceRun.Item(
        name, path, null, null, -1, null, group.encoding(), group.digest(), spans, null);
  }

  /** Applies a document's replacements as one undo step; see {@link ReplaceRun.Buffers}. */
  private ReplaceRun.Row applyToDocument(ReplaceRun.Item item, List<Replacement> replacements) {
    Document d = item.document();
    ReplaceRun.Reason reason = null;
    DocumentController holder = documents.holderOf(d);
    if (holder == null) {
      reason = ReplaceRun.Reason.CLOSED;
    } else if (d.revision() != item.revision() || d.preeditRange() != null) {
      reason = ReplaceRun.Reason.CHANGED;
    } else if (d.isReadOnly()) {
      reason = ReplaceRun.Reason.READ_ONLY;
    }
    if (reason != null) {
      return new ReplaceRun.Row(
          item.name(), item.path(), ReplaceRun.Status.SKIPPED, reason, 0, null);
    }
    EditBatch batch = EditBatch.of(EditBatch.Kind.COMMAND, replacements);
    EditorController editor = holder.editorOf(d);
    if (editor == null) {
      d.apply(batch);
    } else {
      int caret = editor.selections().selections().primary().caret();
      d.apply(
          batch,
          editor.selections(),
          SelectionSet.caret(FindController.mapped(caret, replacements)));
    }
    return new ReplaceRun.Row(
        item.name(), item.path(), ReplaceRun.Status.CHANGED, null, replacements.size(), null);
  }

  /**
   * Reports whether a replace or restore runs.
   *
   * @return true while one runs
   */
  boolean running() {
    for (Report r : reports) {
      if (r.run != null && !r.run.isFinished()) {
        return true;
      }
    }
    return false;
  }

  // Restore Replace Run.

  /** Lists the runs kept on a worker, then asks which one to restore. */
  public void chooseRun() {
    if (disposed) {
      return;
    }
    if (running()) {
      view.showNotice(UiText.REPLACE_RUNNING.text());
      return;
    }
    final Path root = backupRoot;
    final int days = backupDays;
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                listRuns(root, days);
              }
            });
    if (!started) {
      view.showNotice(UiText.REPLACE_RUNNING.text());
    }
  }

  @WorkerThread
  private void listRuns(Path root, int days) {
    List<ReplaceBackups.Run> runs = null;
    String failure = null;
    List<String> labels = new ArrayList<String>();
    try {
      ReplaceBackups.prune(root, days, Instant.now());
      runs = ReplaceBackups.list(root);
      ZoneId zone = ZoneId.systemDefault();
      for (ReplaceBackups.Run r : runs) {
        labels.add(
            UiText.RESTORE_RUN_ROW.format(RUN_TIME.format(r.started().atZone(zone)), r.files()));
      }
    } catch (IOException e) {
      failure = LogText.oneLine(String.valueOf(e.getMessage()));
    }
    final List<ReplaceBackups.Run> found = runs;
    final String failed = failure;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            listed(found, labels, failed);
          }
        });
  }

  private void listed(final List<ReplaceBackups.Run> runs, List<String> labels, String failure) {
    if (disposed) {
      return;
    }
    if (failure != null) {
      view.showInfo(UiText.RESTORE_CHOOSE_TITLE.text(), UiText.RESTORE_LIST_FAILED.format(failure));
    } else if (runs.isEmpty()) {
      view.showInfo(UiText.RESTORE_CHOOSE_TITLE.text(), UiText.RESTORE_NO_RUNS.text());
    } else {
      view.chooseReplaceRun(
          labels,
          new Consumer<Integer>() {
            @Override
            public void accept(Integer index) {
              ReplaceBackups.Run run = runs.get(index);
              restore(run.directory(), run.id());
            }
          });
    }
  }

  /** Starts restoring a run's backups in a report of its own. */
  private void restore(Path directory, String id) {
    if (disposed) {
      return;
    }
    if (running()) {
      view.showNotice(UiText.REPLACE_RUNNING.text());
      return;
    }
    Report report = new Report(UiText.RESTORE_TITLE.format(id), null);
    reports.add(report);
    view.showReplaceReport(report);
    report.start(ReplaceRun.restore(directory, workers, ui, report));
  }

  /**
   * Sets the days a run's backups are kept ({@code replace.backupDays}); the next prune reads it.
   *
   * @param days the days
   */
  void setBackupDays(int days) {
    backupDays = days;
  }

  /**
   * Sets the longest line a run reads ({@code search.maxLineChars}); the next run reads it.
   *
   * @param chars the longest line
   */
  void setMaxLineChars(int chars) {
    limits = limits.withMaxLineChars(chars);
  }

  /** Stops every run; a file in progress finishes or is left untouched. */
  public void dispose() {
    disposed = true;
    closed();
    for (Report r : reports) {
      if (r.run != null) {
        r.run.cancel();
      }
    }
    reports.clear();
  }

  /**
   * Returns the reports of this window's runs, oldest first; for tests.
   *
   * @return the reports
   */
  List<ReportInput> reports() {
    return new ArrayList<ReportInput>(reports);
  }

  // Reports.

  /** One run's report; {@code groups} are the replaced groups by row, null for a restore. */
  private final class Report implements ReportInput, ReplaceRun.Listener {
    final String title;
    final List<FileResult> groups;
    ReplaceRun run;
    boolean restoring;

    Report(String title, List<FileResult> groups) {
      this.title = title;
      this.groups = groups;
    }

    void start(ReplaceRun started) {
      run = started;
      if (run.isFinished()) {
        finished();
      }
    }

    @Override
    public void changed() {
      if (!disposed && run != null) {
        view.replaceReportChanged(this);
      }
    }

    @Override
    public void finished() {
      if (run == null || disposed) {
        return;
      }
      if (groups != null) {
        List<ReplaceRun.Row> rows = run.rows();
        for (int i = 0; i < rows.size(); i++) {
          FileResult group = groups.get(i);
          if (rows.get(i).status() == ReplaceRun.Status.CHANGED && group.document() == null) {
            group.markStale();
          }
        }
        view.resultsChanged(true);
        documents.refreshOverview();
        view.documentStateChanged();
      } else {
        for (ReplaceRun.Row r : run.rows()) {
          if (r.status() == ReplaceRun.Status.CHANGED && r.path() != null) {
            documents.checkFile(r.path());
          }
        }
      }
      view.replaceReportChanged(this);
    }

    @Override
    public String title() {
      return title;
    }

    @Override
    public String summary() {
      if (run == null) {
        return "";
      }
      int changed = run.count(ReplaceRun.Status.CHANGED);
      int skipped = run.count(ReplaceRun.Status.SKIPPED);
      int failed = run.count(ReplaceRun.Status.FAILED);
      int waiting = run.count(ReplaceRun.Status.NOT_ATTEMPTED);
      if (!run.isFinished() && groups != null) {
        return UiText.REPLACE_REPORT_PROGRESS.format(
            run.rows().size() - waiting, run.rows().size());
      }
      String text = UiText.REPLACE_REPORT_DONE.format(changed, skipped, failed, waiting);
      return run.backups() == null
          ? text
          : text + UiText.REPLACE_REPORT_BACKUPS.format(run.backups().directory());
    }

    @Override
    public int rowCount() {
      return run == null ? 0 : run.rows().size();
    }

    @Override
    public Row row(int row) {
      ReplaceRun current = run;
      if (current == null) {
        return new Row("", "", "", false);
      }
      ReplaceRun.Row r = current.rows().get(row);
      Path parent = r.path() == null ? null : r.path().getParent();
      String result =
          switch (r.status()) {
            case CHANGED ->
                groups == null
                    ? UiText.REPLACE_ROW_RESTORED.text()
                    : UiText.REPLACE_ROW_CHANGED.format(r.replaced());
            case SKIPPED -> UiText.REPLACE_ROW_SKIPPED.format(reasonText(r.reason()));
            case FAILED -> UiText.REPLACE_ROW_FAILED.format(r.error());
            case NOT_ATTEMPTED -> UiText.REPLACE_ROW_NOT_ATTEMPTED.text();
          };
      return new Row(
          r.name(),
          parent == null ? "" : parent.toString(),
          result,
          r.status() == ReplaceRun.Status.SKIPPED || r.status() == ReplaceRun.Status.FAILED);
    }

    @Override
    public boolean running() {
      return run != null && !run.isFinished();
    }

    @Override
    public void cancel() {
      if (run != null) {
        run.cancel();
      }
    }

    @Override
    public boolean canRestore() {
      return groups != null
          && run != null
          && run.isFinished()
          && run.backups() != null
          && !restoring;
    }

    @Override
    public void restore() {
      ReplaceRun current = run;
      if (current == null || !canRestore() || ReplaceController.this.running()) {
        return;
      }
      ReplaceBackups backups = current.backups();
      restoring = true;
      view.replaceReportChanged(this);
      ReplaceController.this.restore(backups.directory(), backups.id());
    }
  }

  /**
   * Returns the text of a skip reason.
   *
   * @param reason the reason
   * @return the text
   */
  static String reasonText(ReplaceRun.Reason reason) {
    return switch (reason) {
      case MISSING -> UiText.REASON_MISSING.text();
      case CHANGED -> UiText.REASON_CHANGED.text();
      case READ_ONLY -> UiText.REASON_READ_ONLY.text();
      case UNREADABLE -> UiText.REASON_UNREADABLE.text();
      case TOO_LARGE -> UiText.REASON_TOO_LARGE.text();
      case BINARY -> UiText.REASON_BINARY.text();
      case MALFORMED -> UiText.REASON_MALFORMED.text();
      case NO_ROUND_TRIP -> UiText.REASON_NO_ROUND_TRIP.text();
      case NO_MATCHES -> UiText.REASON_NO_MATCHES.text();
      case UNMAPPABLE -> UiText.REASON_UNMAPPABLE.text();
      case NO_MEMORY -> UiText.REASON_NO_MEMORY.text();
      case CLOSED -> UiText.REASON_CLOSED.text();
      case TOO_COMPLEX -> UiText.REASON_TOO_COMPLEX.text();
      case UNCHANGED -> UiText.REASON_UNCHANGED.text();
      case CHANGED_SINCE -> UiText.REASON_CHANGED_SINCE.text();
    };
  }
}
