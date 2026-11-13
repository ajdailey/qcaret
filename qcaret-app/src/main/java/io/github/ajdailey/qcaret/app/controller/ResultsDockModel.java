package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileRerun;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.search.ReplaceRun;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HyperSearch's results dock: whether it shows, the label of each set, group and match row, the
 * dock's commands on a set or group, and the sets kept in the session. Confined to the UI thread.
 */
final class ResultsDockModel {
  private final SearchController search;
  private final List<SearchController.Entry> entries;
  private final ResultsNavigation navigation;
  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private boolean shown;

  ResultsDockModel(
      SearchController search,
      List<SearchController.Entry> entries,
      ResultsNavigation navigation,
      DocumentController documents,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget) {
    this.search = search;
    this.entries = entries;
    this.navigation = navigation;
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
  }

  /** See {@link SearchController#resultsShown}. */
  boolean resultsShown() {
    return shown;
  }

  /** See {@link SearchController#toggleResults}. */
  void toggleResults() {
    setShown(!shown);
  }

  /** Shows the results dock, or hides it. */
  void setShown(boolean value) {
    shown = value;
    view.showResults(search, value);
    view.documentStateChanged();
    documents.sessionChanged();
  }

  /** See {@link SearchController#retained}. */
  List<Session.Search> retained() {
    List<Session.Search> list = new ArrayList<Session.Search>();
    for (SearchController.Entry e : entries) {
      if (e.saved && e.set.request().text().length() <= UiState.MAX_ENTRY_CHARS) {
        list.add(new Session.Search(e.set.request(), e.set.pinned()));
      }
    }
    return list;
  }

  /** See {@link SearchController#restore}. */
  void restore(List<Session.Search> searches, boolean show) {
    for (int i = searches.size() - 1; i >= 0; i--) {
      Session.Search s = searches.get(i);
      entries.add(
          0,
          new SearchController.Entry(ResultSet.restored(search.newId(), s.request(), s.pinned())));
    }
    search.evict();
    if (show) {
      setShown(true);
    }
    if (!searches.isEmpty()) {
      view.resultsChanged(true);
    }
  }

  /** See {@link SearchController#hasResults}. */
  boolean hasResults() {
    for (SearchController.Entry e : entries) {
      if (e.set.matches() > 0) {
        return true;
      }
    }
    return false;
  }

  /** See {@link SearchController#sets}. */
  List<ResultSet> sets() {
    List<ResultSet> list = new ArrayList<ResultSet>();
    for (SearchController.Entry e : entries) {
      list.add(e.set);
    }
    return list;
  }

  /** See {@link SearchController#running()}. */
  boolean running() {
    if (search.resolving()) {
      return true;
    }
    for (SearchController.Entry e : entries) {
      if (e.run != null || e.rerun != null) {
        return true;
      }
    }
    return false;
  }

  ResultLabel set(int set) {
    SearchController.Entry e = entries.get(set);
    ResultSet r = e.set;
    SearchRequest q = r.request();
    String scope = q.scope().label() + (q.folder() == null ? "" : " " + q.folder());
    StringBuilder text =
        new StringBuilder(
            UiText.SEARCH_SET.format(
                q.text(),
                scope,
                UiText.count(r.matches(), UiText.COUNT_MATCH, UiText.COUNT_MATCHES),
                UiText.count(r.files().size(), UiText.COUNT_FILE, UiText.COUNT_FILES)));
    List<String> notes = new ArrayList<String>();
    switch (r.state()) {
      case RUNNING ->
          notes.add(
              UiText.SEARCH_RUNNING.format(
                  r.searched(), r.total() < 0 ? "?" : String.valueOf(r.total())));
      case CANCELED -> notes.add(UiText.SEARCH_CANCELED.text());
      case TRUNCATED ->
          notes.add(
              r.noMemory()
                  ? UiText.SEARCH_NO_MEMORY.text()
                  : UiText.SEARCH_TRUNCATED.format(r.matches()));
      case RESTORED -> notes.add(UiText.SEARCH_RESTORED.text());
      case DONE -> {
        // No note.
      }
    }
    if (e.rerun != null) {
      notes.add(UiText.SEARCH_RERUNNING.text());
    }
    List<String> skipped = new ArrayList<String>();
    count(skipped, UiText.SEARCH_SKIPPED_BINARY, r.binary());
    count(skipped, UiText.SEARCH_SKIPPED_LARGE, r.tooLarge());
    count(skipped, UiText.SEARCH_SKIPPED_UNREADABLE, r.unreadable());
    count(skipped, UiText.SEARCH_SKIPPED_LINES, r.skippedLines());
    count(skipped, UiText.SEARCH_SKIPPED_COMPLEX, r.tooComplex());
    if (!skipped.isEmpty()) {
      notes.add(UiText.SEARCH_SKIPPED.format(String.join(", ", skipped)));
    }
    if (r.pinned()) {
      notes.add(UiText.SEARCH_PINNED.text());
    }
    for (String note : notes) {
      text.append("; ").append(note);
    }
    return ResultLabel.plain(text.toString(), false);
  }

  private static void count(List<String> parts, UiText label, int count) {
    if (count > 0) {
      parts.add(label.format(count));
    }
  }

  ResultLabel file(int set, int file) {
    FileResult f = search.group(set, file);
    f.rebase();
    Path parent = f.path() == null ? null : f.path().getParent();
    StringBuilder text =
        new StringBuilder(
            UiText.SEARCH_FILE.format(
                f.name(), parent == null ? "" : parent.toString(), f.matchCount()));
    if (f.tooComplexLine() >= 0) {
      text.append("; ").append(UiText.SEARCH_FILE_COMPLEX.format(f.tooComplexLine() + 1));
    }
    if (f.skippedLines() > 0) {
      text.append("; ").append(UiText.SEARCH_FILE_LINES.format(f.skippedLines()));
    }
    if (f.stale()) {
      text.append("; ").append(UiText.SEARCH_CHANGED.text());
    }
    return ResultLabel.plain(text.toString(), f.stale());
  }

  ResultLabel match(int set, int file, int match) {
    FileResult f = search.group(set, file);
    f.rebase();
    TextSearch.Hit hit = f.hit(match);
    String prefix = (f.line(match) + 1) + ": ";
    return new ResultLabel(
        prefix + hit.snippet(),
        prefix.length() + hit.snippetStart(),
        prefix.length() + hit.snippetEnd(),
        f.stale(match));
  }

  void expanded(int set, int file) {
    FileResult f = search.group(set, file);
    if (f.document() == null && f.stamp() != null && !f.stale()) {
      navigation.check(f, null);
    }
  }

  /** See {@link SearchController#rerun}. */
  void rerun(int set) {
    SearchController.Entry e = entries.get(set);
    if (e.run != null) {
      e.run.cancel();
    }
    if (e.rerun != null) {
      e.rerun.cancel();
    }
    search.start(e.set.request(), e);
  }

  void rerunFile(final int set, int file) {
    final SearchController.Entry e = entries.get(set);
    if (e.run != null || e.rerun != null) {
      view.showNotice(UiText.SEARCH_NOT_FINISHED.text());
      return;
    }
    if (e.set.request().scope() == SearchScope.SELECTION) {
      rerun(set);
      return;
    }
    final FileResult group = e.set.files().get(file);
    Document document = group.document();
    if (document != null && documents.holderOf(document) == null) {
      group.detach();
      document = null;
    }
    if (document == null && group.path() != null) {
      document = documents.documentAnywhere(group.path());
    }
    if (document == null && group.path() == null) {
      view.showNotice(UiText.SEARCH_CLOSED.text());
      return;
    }
    final String name =
        document == null ? group.name() : documents.holderOf(document).nameOf(document);
    final ResultSet results = e.set;
    FileRerun.start(
        results,
        group,
        results.request().query().compile(),
        document,
        group.path(),
        name,
        search.limits(),
        workers,
        ui,
        budget,
        new FileRerun.Listener() {
          @Override
          public void finished(int matches, FileSearch.Skip skip) {
            if (search.disposed()) {
              return;
            }
            if (skip != null) {
              view.showNotice(
                  UiText.SEARCH_RERUN_FILE_SKIPPED.format(
                      name, ReplaceController.reasonText(ReplaceRun.Reason.valueOf(skip.name()))));
            } else if (matches == 0) {
              view.showNotice(UiText.SEARCH_RERUN_FILE_EMPTY.format(name));
            }
            navigation.restart(results.id());
            view.resultsChanged(true);
            documents.refreshOverview();
            view.documentStateChanged();
          }
        });
  }

  void replace(int set) {
    SearchController.Entry e = entries.get(set);
    if (e.run != null || e.rerun != null) {
      view.showNotice(UiText.SEARCH_NOT_FINISHED.text());
      return;
    }
    ReplaceController replace = search.replaceController();
    if (replace != null) {
      replace.preview(e.set);
    }
  }

  /** See {@link SearchController#replaceInResults}. */
  void replaceInResults() {
    int set = navigation.lastSet();
    if (set < 0 || entries.get(set).set.matches() == 0) {
      set = -1;
      for (int i = 0; i < entries.size() && set < 0; i++) {
        if (entries.get(i).set.matches() > 0) {
          set = i;
        }
      }
    }
    if (set < 0) {
      view.showNotice(UiText.SEARCH_NO_RESULTS.text());
      return;
    }
    replace(set);
  }

  void stop(int set) {
    SearchController.Entry e = entries.get(set);
    if (e.run != null) {
      e.run.cancel();
    }
    if (e.rerun != null) {
      e.rerun.cancel();
    }
  }

  void togglePin(int set) {
    ResultSet r = entries.get(set).set;
    r.setPinned(!r.pinned());
    search.evict();
    view.resultsChanged(true);
  }

  void clear(int set) {
    search.drop(entries.remove(set));
    view.resultsChanged(true);
    documents.refreshOverview();
    view.documentStateChanged();
  }

  void clearAll() {
    for (SearchController.Entry e : entries) {
      search.drop(e);
    }
    entries.clear();
    view.resultsChanged(true);
    documents.refreshOverview();
    view.documentStateChanged();
  }

  void hide() {
    if (shown) {
      setShown(false);
    }
  }
}
