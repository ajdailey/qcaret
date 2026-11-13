package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.FoldRegionSource;
import io.github.ajdailey.qcaret.app.editor.IndentFoldSource;
import io.github.ajdailey.qcaret.app.editor.MergedFoldSource;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * One view's projection work: rewraps, fold regions, the fold commands, and the folds and top line
 * {@link EditorController#restoreView} puts back.
 *
 * <ul>
 *   <li>Each rewrap runs on a {@code qcaret-cpu-} worker; a newer request cancels the running one.
 *   <li>Fold regions come from a {@link FoldRegionSource} on a {@code qcaret-cpu-} worker, {@value
 *       #FOLD_DELAY_MILLIS} ms after the last change, one run at a time; a result for an older
 *       revision is dropped and the regions requested again.
 *   <li>Folding moves every caret out of the hidden lines, to the end of the region's first line; a
 *       primary caret placed in hidden lines by any other command expands the regions around it.
 * </ul>
 *
 * <p>Confined to the UI thread.
 */
final class FoldScheduling {
  /** Milliseconds from a change to the fold region update it starts. */
  static final int FOLD_DELAY_MILLIS = 300;

  private final EditorController editor;
  private final Document document;
  private final DisplayProjection projection;
  private final SelectionModel selections;
  private final Workers workers;
  private final UiDispatcher ui;
  private CancellationToken rewrap;
  private FoldRegionSource foldSource;

  /** True when indentation regions join {@link #foldSource}'s, or stand alone without one. */
  private boolean indentFolds = true;

  private boolean foldsRunning;
  private DisplayProjection.FoldAll foldAll;
  private boolean foldsAgain;

  /** The line {@link #restore} scrolled to, until the view reports its viewport. */
  private int restoredTop = -1;

  /** The line a restored view scrolls back to after each rewrap, or -1. */
  private int wrapTop = -1;

  /** The lines {@link #restore} collapses as regions arrive, until an edit or a fold command. */
  private int[] restoredFolds;

  private int foldsTop;

  private final Runnable foldTimer =
      new Runnable() {
        @Override
        public void run() {
          scheduleFolds();
        }
      };
  private final SelectionListener revealer =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          revealPrimary();
        }
      };
  private final DocumentListener foldFollower =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          restoredFolds = null;
          restoredTop = -1;
          wrapTop = -1;
          ui.cancelTimer(foldTimer);
          ui.timerExec(FOLD_DELAY_MILLIS, foldTimer);
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Fold regions do not depend on the dirty state.
        }
      };

  /** Hooks the projection's rewraps, follows the selections and text, and requests regions. */
  FoldScheduling(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.projection = editor.projection();
    this.selections = editor.selections();
    this.workers = workers;
    this.ui = ui;
    projection.setRewrapHook(
        new Runnable() {
          @Override
          public void run() {
            scheduleRewrap();
          }
        });
    selections.addListener(revealer);
    document.addListener(foldFollower);
    scheduleFolds();
  }

  /** Cancels a running rewrap and the fold timer and stops following the selections and text. */
  void dispose() {
    if (rewrap != null) {
      rewrap.cancel();
    }
    ui.cancelTimer(foldTimer);
    selections.removeListener(revealer);
    document.removeListener(foldFollower);
  }

  /** Requests indentation regions again at once when they take part. */
  void tabWidthChanged() {
    if (indentFolds) {
      ui.cancelTimer(foldTimer);
      ui.timerExec(0, foldTimer);
    }
  }

  /** Stops scrolling back to a restored top line once the view reports its viewport. */
  void viewportChanged() {
    restoredTop = -1;
    if (rewrap == null) {
      wrapTop = -1;
    }
  }

  /**
   * Scrolls to a restored top line and collapses each fold once a region starts on its line,
   * scrolling to the top line again after it.
   *
   * @param line the zero-based first visible line, within the text
   * @param folds the first lines of the regions to collapse
   */
  void restore(int line, int[] folds) {
    restoredFolds = folds.length == 0 ? null : folds;
    foldsTop = line;
    applyRestoredFolds();
    restoredTop = line;
    wrapTop = line;
    EditorView view = editor.view();
    if (view != null) {
      view.scrollToRow(projection.rowOfLine(line));
    }
  }

  private void applyRestoredFolds() {
    if (restoredFolds == null) {
      return;
    }
    boolean folded = false;
    for (int line : restoredFolds) {
      folded |= !projection.collapsed(line) && projection.foldAt(line);
    }
    if (folded) {
      caretsOutOfFolds();
      EditorView view = editor.view();
      if (view != null) {
        view.scrollToRow(projection.rowOfLine(foldsTop));
      }
    }
  }

  /** Returns the restored top line until the view reports its viewport, else -1. */
  int restoredTop() {
    return restoredTop;
  }

  /** Starts the rewrap the projection needs, canceling the running one. */
  void scheduleRewrap() {
    if (editor.disposed()) {
      return;
    }
    final DisplayProjection.Rewrap request = projection.rewrapRequest();
    if (request == null) {
      return;
    }
    if (rewrap != null) {
      rewrap.cancel();
    }
    final CancellationToken token = new CancellationToken();
    rewrap = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final DisplayProjection.Layout rows;
              try {
                rows = DisplayProjection.compute(request, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (!editor.disposed()) {
                        projection.install(request, rows);
                        if (rewrap == token) {
                          rewrap = null;
                        }
                        scheduleRewrap();
                        EditorView view = editor.view();
                        if (wrapTop >= 0 && view != null) {
                          view.scrollToRow(projection.rowOfLine(wrapTop));
                        }
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      rewrap = null;
    }
  }

  /**
   * Sets where fold regions come from and requests them again.
   *
   * @param source the source, or null for indentation folding at the document's tab width
   * @param indent true when indentation regions join the source's
   */
  void setSource(FoldRegionSource source, boolean indent) {
    indentFolds = indent;
    foldSource = source;
    ui.cancelTimer(foldTimer);
    ui.timerExec(0, foldTimer);
  }

  /** Runs the fold step again when the regions come from {@code source}, which has new ones. */
  void sourceChanged(FoldRegionSource source) {
    if (foldSource != null && foldSource == source) {
      scheduleFolds();
    }
  }

  /** Toggles the fold at a view row, as a click in the fold margin does. */
  void toggleFromView(int row) {
    restoredFolds = null;
    int line = projection.lineOfRow(Math.max(0, Math.min(row, projection.rowCount() - 1)));
    if (!projection.unfold(line) && projection.foldAt(line)) {
      caretsOutOfFolds();
    }
  }

  /** Runs Fold, Unfold, Toggle Fold, Fold All or Unfold All at the primary caret's line. */
  void fold(CommandId command) {
    restoredFolds = null;
    foldAll = null;
    if (command == CommandId.FOLD_ALL) {
      requestFoldAll();
      return;
    }
    int line = projection.lineAt(selections.selections().primary().caret());
    boolean changed =
        switch (command) {
          case FOLD -> projection.fold(line);
          case UNFOLD -> projection.unfold(line);
          case TOGGLE_FOLD -> projection.unfold(line) || projection.fold(line);
          default -> projection.unfoldAll();
        };
    if (changed) {
      caretsOutOfFolds();
      editor.showCaret();
    }
  }

  /**
   * Measures Fold All's spans on a worker and collapses them at the same text and rows; asks again
   * when those changed meanwhile.
   */
  private void requestFoldAll() {
    final DisplayProjection.FoldAll request = projection.foldAllRequest();
    if (request == null || editor.disposed()) {
      return;
    }
    foldAll = request;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                DisplayProjection.computeFoldAll(request);
              } finally {
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        foldAllComputed(request);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      foldAll = null;
    }
  }

  private void foldAllComputed(DisplayProjection.FoldAll request) {
    if (editor.disposed() || request != foldAll) {
      return;
    }
    foldAll = null;
    switch (projection.installFoldAll(request)) {
      case APPLIED -> {
        caretsOutOfFolds();
        editor.showCaret();
      }
      case STALE -> requestFoldAll();
      default -> {
        // A fold command since the request decides the collapsed regions.
      }
    }
  }

  /** Moves every caret and anchor in hidden lines to the end of the line before them. */
  private void caretsOutOfFolds() {
    if (selections.block() != null) {
      return;
    }
    SelectionSet set = selections.selections();
    List<Selection> out = new ArrayList<Selection>(set.size());
    boolean moved = false;
    for (Selection s : set.selections()) {
      int anchor = projection.visibleOffset(s.anchor(), false);
      int caret = projection.visibleOffset(s.caret(), false);
      moved |= anchor != s.anchor() || caret != s.caret();
      out.add(Selection.of(anchor, caret));
    }
    if (moved) {
      selections.set(SelectionSet.of(out, set.primaryIndex()));
    }
  }

  /** Expands the regions that hide the primary caret. */
  private void revealPrimary() {
    if (projection.collapsedCount() > 0) {
      projection.reveal(projection.lineAt(selections.selections().primary().caret()));
    }
  }

  /** Computes the fold regions on a worker, after the one running. */
  private void scheduleFolds() {
    if (editor.disposed()) {
      return;
    }
    if (foldsRunning) {
      foldsAgain = true;
      return;
    }
    FoldRegionSource indent = new IndentFoldSource(document.tabWidth());
    final FoldRegionSource source =
        foldSource == null
            ? indent
            : indentFolds ? new MergedFoldSource(foldSource, indent) : foldSource;
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    foldsRunning = true;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              List<int[]> regions = null;
              try {
                regions = source.regions(text, revision);
              } finally {
                final List<int[]> result = regions;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        foldsComputed(revision, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      foldsRunning = false;
    }
  }

  /** Installs computed regions while the document is at their revision; null after a failure. */
  private void foldsComputed(long revision, List<int[]> regions) {
    foldsRunning = false;
    if (editor.disposed()) {
      return;
    }
    if (foldsAgain) {
      foldsAgain = false;
      scheduleFolds();
      return;
    }
    if (regions != null && document.revision() == revision) {
      projection.setFoldRegions(regions);
      applyRestoredFolds();
    }
  }
}
