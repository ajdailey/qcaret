package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One Quit that asks. Windows go most recently activated first, each dirty document in tab order:
 * every document is asked about while nothing closes or saves, then the Save answers run in the
 * same order, then the run waits for every running Save a Copy As ({@link RunningCopies}) and reads
 * the windows again, then the session takes its windows, every document closes and {@link
 * WindowSet#quit} runs. Until then each dirty buffer stays open and keeps its recovery file.
 *
 * <p>Each step reads the windows again, so a tab moved, a file handed or an edit made meanwhile is
 * covered: an answer belongs to the {@link Document}, a document dirty without one is asked about,
 * and Don't Save stands only for the revision it was given on. A Cancel selects the tabs selected
 * at the start again and raises the window that was in front. A save that writes no file (a
 * failure, a canceled chooser, a declined prompt) or a failed copy stops the quit with that tab
 * selected and the documents not yet saved still open. Confined to the UI thread.
 */
final class QuitPrompts {
  /** What the user said about a document at a revision. */
  private record Answer(UnsavedChoice choice, long revision) {}

  private final WindowSet set;
  private final SessionController session;
  private final Runnable stopped;
  private final Map<Document, Answer> answers = new IdentityHashMap<Document, Answer>();
  private final Map<ApplicationController, Document> selected =
      new HashMap<ApplicationController, Document>();
  private final List<ApplicationController> order;
  private boolean raised;
  private boolean ended;

  /**
   * Creates the run.
   *
   * @param set the windows
   * @param session holds its saves during the run
   * @param stopped runs when the quit does not go through
   */
  QuitPrompts(WindowSet set, SessionController session, Runnable stopped) {
    this.set = set;
    this.session = session;
    this.stopped = stopped;
    this.order = set.activationOrder();
  }

  /** Asks about the first dirty document, or quits when there is none. */
  void start() {
    for (ApplicationController window : order) {
      selected.put(window, window.documents().activeDocument());
    }
    session.quitStarted();
    next();
  }

  private void next() {
    if (ended || set.isQuitting()) {
      return;
    }
    List<ApplicationController> windows = set.activationOrder();
    for (int i = windows.size() - 1; i >= 0; i--) {
      for (Document document : windows.get(i).documents().dirtyDocuments()) {
        if (unanswered(document)) {
          ask(windows.get(i), document);
          return;
        }
      }
    }
    for (int i = windows.size() - 1; i >= 0; i--) {
      for (Document document : windows.get(i).documents().dirtyDocuments()) {
        if (answers.get(document).choice() == UnsavedChoice.SAVE) {
          save(windows.get(i), document);
          return;
        }
      }
    }
    if (set.copies().any()) {
      set.copies().await(this::next, this::stop);
      return;
    }
    finish(windows);
  }

  private boolean unanswered(Document document) {
    Answer answer = answers.get(document);
    return answer == null
        || (answer.choice() == UnsavedChoice.DISCARD && answer.revision() != document.revision());
  }

  private void raise(ApplicationController window) {
    if (set.windows().size() > 1) {
      window.toFront();
      raised = true;
    }
  }

  private void ask(ApplicationController window, final Document document) {
    raise(window);
    final long revision = document.revision();
    window
        .documents()
        .askBeforeQuit(
            document,
            new Consumer<UnsavedChoice>() {
              @Override
              public void accept(UnsavedChoice choice) {
                answered(document, choice, revision);
              }
            });
  }

  private void answered(Document document, UnsavedChoice choice, long revision) {
    if (ended || set.isQuitting()) {
      return;
    }
    if (choice == UnsavedChoice.CANCEL) {
      restoreSelection();
      if (raised && !order.isEmpty()) {
        order.get(order.size() - 1).toFront();
      }
      stop();
    } else {
      answers.put(document, new Answer(choice, revision));
      next();
    }
  }

  private void save(ApplicationController window, Document document) {
    answers.remove(document);
    raise(window);
    window.documents().saveForQuit(document, this::next, this::stop);
  }

  private void restoreSelection() {
    for (ApplicationController window : order) {
      window.documents().reselect(selected.get(window));
      set.activated(window);
    }
  }

  private void stop() {
    if (!ended && !set.isQuitting()) {
      ended = true;
      session.quitCanceled();
      stopped.run();
    }
  }

  private void finish(List<ApplicationController> windows) {
    ended = true;
    restoreSelection();
    session.quitAnswered();
    for (ApplicationController window : windows) {
      window.documents().closeForQuit();
    }
    set.quit();
  }
}
