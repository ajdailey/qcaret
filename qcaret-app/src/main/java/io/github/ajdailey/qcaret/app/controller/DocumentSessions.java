package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The session state of one {@link DocumentController}'s documents: a session's tabs and dirty
 * buffers read from the entries, a session tab or a remembered file state applied to an entry, a
 * closing file's state kept in the {@link DocumentController.FileMemory}, and the Reopen Closed Tab
 * list. Confined to the UI thread.
 */
final class DocumentSessions {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentSessions.class);

  private final DocumentController owner;
  private final List<OpenDocument> documents;
  private final Supplier<OpenDocument> selected;
  private final MainView view;
  private final DocumentGrammars grammars;
  private final Deque<Path> closedPaths;
  private DocumentController.FileMemory memory;

  DocumentSessions(
      DocumentController owner,
      List<OpenDocument> documents,
      Supplier<OpenDocument> selected,
      MainView view,
      DocumentGrammars grammars,
      Deque<Path> closedPaths) {
    this.owner = owner;
    this.documents = documents;
    this.selected = selected;
    this.view = view;
    this.grammars = grammars;
    this.closedPaths = closedPaths;
  }

  /** {@link DocumentController#setFileMemory}. */
  void setMemory(DocumentController.FileMemory value) {
    memory = value;
  }

  /** Returns a file's remembered state, or null when none is kept or nothing keeps it. */
  Session.FileState state(Path path) {
    return memory == null ? null : memory.state(path);
  }

  /** Applies a file's remembered state to a document that opened it; none does nothing. */
  void applyFile(OpenDocument d, Path path) {
    Session.FileState state = state(path);
    if (state == null) {
      return;
    }
    d.setTabSettings(state.tabWidth(), state.insertSpaces());
    grammars.restore(d, state.language());
    SelectionSet set = SessionText.selections(state.selections(), d.document.text().length());
    d.editor.restoreView(set, state.top(), new int[0]);
    view.documentStateChanged();
  }

  /** Applies a session tab's state to its document: settings, grammar, marks, views, read-only. */
  void applyTab(OpenDocument d, Session.Tab tab) {
    d.setTabSettings(tab.tabWidth(), tab.insertSpaces());
    grammars.restore(d, tab.language());
    int lines = d.document.text().lineCount();
    for (int line : SessionText.numbers(tab.bookmarks())) {
      if (line < lines && !d.bookmarks.contains(line)) {
        d.bookmarks.toggle(line);
      }
    }
    for (Session.Comment comment : tab.comments()) {
      if (comment.line() < lines) {
        d.bookmarks.setComment(comment.line(), comment.text());
      }
    }
    if (tab.views().size() > 1) {
      if (selected.get() != d) {
        d.tab.select();
      }
      owner.split(d, tab.sideBySide());
      int[] weights = SessionText.numbers(tab.weights());
      if (weights.length == 2 && weights[0] > 0 && weights[1] > 0) {
        d.tab.setSplitWeights(weights);
      }
    }
    for (int i = 0; i < Math.min(tab.views().size(), d.editors.size()); i++) {
      Session.View saved = tab.views().get(i);
      SelectionSet set = SessionText.selections(saved.selections(), d.document.text().length());
      d.editors.get(i).restoreView(set, saved.top(), SessionText.numbers(saved.folds()));
    }
    if (d.editors.size() > 1) {
      d.editor = d.editors.get(Math.min(tab.focused(), d.editors.size() - 1));
      d.editor.view().focus();
    }
    if (tab.readOnly() && !d.document.isReadOnly()) {
      d.document.clearPreedit();
      d.document.setReadOnly(true);
      d.updateTitle();
    }
    view.documentStateChanged();
  }

  private Session.Tab tab(OpenDocument d, String buffer) {
    List<Session.View> views = new ArrayList<Session.View>(2);
    for (EditorController e : d.editors) {
      views.add(
          new Session.View(
              SessionText.selections(e.selections().selections()),
              e.topLine(),
              SessionText.numbers(e.projection().collapsedLines())));
    }
    List<Session.Comment> comments = new ArrayList<Session.Comment>();
    for (int line : d.bookmarks.commentedLines()) {
      comments.add(new Session.Comment(line, d.bookmarks.comment(line)));
    }
    Path file = d.file.target();
    return new Session.Tab(
        file == null ? "" : file.toString(),
        d.untitled == null ? "" : d.untitled,
        buffer,
        d.file.forced == null ? "" : d.file.forced.name(),
        d.manualGrammar ? d.grammar : "",
        d.binary,
        d.document.tabWidth(),
        d.document.insertSpaces(),
        views,
        Math.max(0, d.editors.indexOf(d.editor)),
        d.sideBySide,
        d.editors.size() > 1 ? SessionText.numbers(d.tab.splitWeights()) : "",
        SessionText.numbers(d.bookmarks.lines()),
        comments,
        d.document.isReadOnly());
  }

  /** Keeps a closing file's state in the {@link DocumentController.FileMemory}. */
  void rememberClosed(OpenDocument d) {
    if (d.file.path == null) {
      return;
    }
    if (memory != null && !d.binary && !owner.disposing()) {
      EditorController e = d.editor;
      memory.remember(
          new Session.FileState(
              d.file.path.toString(),
              d.file.forced == null ? "" : d.file.forced.name(),
              d.manualGrammar ? d.grammar : "",
              d.document.tabWidth(),
              d.document.insertSpaces(),
              SessionText.selections(e.selections().selections()),
              e.topLine()));
    }
  }

  /** {@link DocumentController#sessionTabs}. */
  List<Session.Tab> tabs(boolean buffers) {
    List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    for (OpenDocument d : documents) {
      boolean snapshot = buffers && d.document.isDirty() && !d.binary;
      if (d.file.path != null || snapshot) {
        tabs.add(tab(d, snapshot ? d.bufferId : ""));
      }
    }
    return tabs;
  }

  /** {@link DocumentController#sessionActive}. */
  int activeIndex(boolean buffers) {
    int index = 0;
    for (OpenDocument d : documents) {
      if (d == selected.get()) {
        return d.file.path != null || (buffers && d.document.isDirty()) ? index : -1;
      }
      if (d.file.path != null || (buffers && d.document.isDirty() && !d.binary)) {
        index++;
      }
    }
    return -1;
  }

  /** {@link DocumentController#dirtyBuffers}. */
  List<DocumentController.Buffer> dirtyBuffers() {
    List<DocumentController.Buffer> buffers = new ArrayList<DocumentController.Buffer>();
    for (OpenDocument d : documents) {
      if (d.document.isDirty() && !d.binary) {
        Path file = d.file.target();
        buffers.add(
            new DocumentController.Buffer(
                d.bufferId,
                d.document.snapshot(),
                file,
                d.untitled,
                d.file.watch == null ? null : d.file.watch.baseline(),
                d.manualGrammar ? d.grammar : "",
                SessionText.selections(d.editor.selections().selections())));
      }
    }
    return buffers;
  }

  /** {@link DocumentController#closedFiles}. */
  List<String> closedFiles() {
    List<String> list = new ArrayList<String>();
    for (Path path : closedPaths) {
      list.add(path.toString());
    }
    return list;
  }

  /** {@link DocumentController#restoreClosed}. */
  void restoreClosed(List<String> files) {
    for (String file : files) {
      try {
        if (closedPaths.size() < DocumentController.REOPEN_LIMIT) {
          closedPaths.addLast(Path.of(file));
        }
      } catch (InvalidPathException e) {
        LOG.debug("A closed file's path does not parse");
      }
    }
  }
}
