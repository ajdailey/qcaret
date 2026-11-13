package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The closes of one {@link DocumentController}'s tabs: the order of a bulk close ({@link
 * TabUse#nextToClose}), the unsaved-changes prompt and the {@link LastTab} rule for a user's close.
 * A close that ends discards the entry through {@link DocumentController#discard}. Confined to the
 * UI thread.
 */
final class TabClosing {
  private final DocumentController owner;
  private final List<OpenDocument> documents;
  private final Supplier<OpenDocument> selected;
  private final MainView view;
  private final SaveAndReload files;
  private LastTab lastTab;
  private final Runnable lastClosed =
      new Runnable() {
        @Override
        public void run() {
          if (documents.isEmpty() && !owner.disposing() && lastTab != null) {
            lastTab.emptied(owner);
          }
        }
      };

  TabClosing(
      DocumentController owner,
      List<OpenDocument> documents,
      Supplier<OpenDocument> selected,
      MainView view,
      SaveAndReload files) {
    this.owner = owner;
    this.documents = documents;
    this.selected = selected;
    this.view = view;
    this.files = files;
  }

  /** {@link DocumentController#setLastTab}. */
  void setLastTab(LastTab rule) {
    lastTab = rule;
  }

  /** Closes a document as the user's close, which {@link LastTab} may keep open. */
  void closeByUser(OpenDocument d) {
    if (!keepsLast(d)) {
      close(d, lastClosed);
    }
  }

  private boolean keepsLast(OpenDocument d) {
    return lastTab != null && documents.size() == 1 && documents.get(0) == d && lastTab.keeps(d);
  }

  /** {@link DocumentController#closeAllTabs}. */
  void closeAllTabs() {
    closeNext(null, lastClosed);
  }

  /** {@link DocumentController#closeAll}. */
  void closeAll(Runnable then) {
    closeNext(null, then);
  }

  /** Closes every document but {@code kept}, asking for each dirty one; Cancel stops. */
  void closeOthers(OpenDocument kept) {
    closeNext(kept, lastClosed);
  }

  /**
   * Closes the next document other than {@code kept} as {@link TabUse#nextToClose} orders them,
   * asking when dirty, then the next.
   */
  private void closeNext(final OpenDocument kept, final Runnable then) {
    List<OpenDocument> candidates = new ArrayList<OpenDocument>(documents);
    candidates.remove(kept);
    OpenDocument next = TabUse.nextToClose(candidates, selected.get());
    showKeptBefore(next, kept);
    if (next != null && then == lastClosed && keepsLast(next)) {
      next.tab.select();
      return;
    }
    if (next == null) {
      if (kept != null && !kept.closed) {
        kept.tab.select();
      }
      if (then != null) {
        then.run();
      }
      return;
    }
    close(
        next,
        new Runnable() {
          @Override
          public void run() {
            closeNext(kept, then);
          }
        });
  }

  /** Closes the tabs on one side of a tab in tab order, asking for each dirty one; Cancel stops. */
  void closeSide(OpenDocument from, boolean left) {
    int at = documents.indexOf(from);
    List<OpenDocument> side =
        new ArrayList<OpenDocument>(
            left ? documents.subList(0, at) : documents.subList(at + 1, documents.size()));
    closeEach(side, from, selected.get());
  }

  /**
   * Closes a list in {@link TabUse#nextToClose} order; {@code kept} is selected once {@code shown}
   * has closed.
   */
  private void closeEach(
      final List<OpenDocument> list, final OpenDocument kept, final OpenDocument shown) {
    OpenDocument next = TabUse.nextToClose(list, selected.get());
    if (next == null) {
      if (shown.closed && !kept.closed) {
        kept.tab.select();
      }
      return;
    }
    showKeptBefore(next, kept);
    close(
        next,
        new Runnable() {
          @Override
          public void run() {
            closeEach(list, kept, shown);
          }
        });
  }

  /** Shows {@code kept} before a bulk close closes the clean shown tab, so no other tab shows. */
  private void showKeptBefore(OpenDocument next, OpenDocument kept) {
    if (next != null
        && next == selected.get()
        && !next.document.isDirty()
        && kept != null
        && !kept.closed) {
      kept.tab.select();
    }
  }

  private void close(OpenDocument d, Runnable then) {
    close(d, then, null);
  }

  /**
   * Closes a document, asking first when it has unsaved changes. Save closes it once the file is
   * written, unless an edit arrived during the write: that asks again.
   *
   * @param d the document
   * @param then runs once it closed
   * @param kept runs instead when the tab stays: a Cancel or a save that wrote no file
   */
  void close(final OpenDocument d, final Runnable then, final Runnable kept) {
    files.cancelSave(d);
    if (!d.document.isDirty()) {
      owner.discard(d);
      if (then != null) {
        then.run();
      }
      return;
    }
    d.tab.select();
    view.askUnsaved(
        d.name(),
        new Consumer<UnsavedChoice>() {
          @Override
          public void accept(UnsavedChoice choice) {
            if (d.closed) {
              if (kept != null) {
                kept.run();
              }
              return;
            }
            final Runnable done =
                new Runnable() {
                  @Override
                  public void run() {
                    owner.discard(d);
                    if (then != null) {
                      then.run();
                    }
                  }
                };
            Runnable written =
                new Runnable() {
                  @Override
                  public void run() {
                    if (d.document.isDirty()) {
                      close(d, then, kept);
                    } else {
                      done.run();
                    }
                  }
                };
            switch (choice) {
              case SAVE -> files.save(d, new SaveAndReload.SaveEnd(written, kept));
              case DISCARD -> done.run();
              case CANCEL -> {
                if (kept != null) {
                  kept.run();
                }
              }
            }
          }
        });
  }
}
