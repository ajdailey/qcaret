package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * A mutable text document: the text in a {@link RopeStorage}, its revision, undo history, line
 * ending counts, encoding and dirty state, plus the selection models and anchors it maps through
 * every change.
 *
 * <p>One thread edits a document, the first to call a mutating method (the UI thread); a mutation
 * from another thread throws {@link IllegalStateException}. Workers read a {@link #snapshot()}.
 * Listeners run on the editing thread and must not edit the document they are told about.
 *
 * <p>Each applied batch, undo and redo step increments {@link #revision()} and fires one {@link
 * DocumentChange}. Dirty is the undo state differing from the saved one, so undoing back to the
 * saved state clears it; a changed encoding also makes the document dirty until the next save.
 */
public final class Document {
  /** Typing more than this many nanoseconds after the last keystroke starts a new undo step. */
  static final long COALESCE_NANOS = 1_000_000_000L;

  private final RopeStorage storage;
  private final UndoHistory history;
  private final LongSupplier clock;
  private final PositionMap positions = new PositionMap(0);
  private final List<DocumentListener> listeners = new ArrayList<DocumentListener>();
  private final List<SelectionModel> models = new ArrayList<SelectionModel>();
  private final List<Anchor> anchors = new ArrayList<Anchor>();
  private final Set<SelectionModel> movedModels = new LinkedHashSet<SelectionModel>();
  private volatile Thread owner;
  private long revision;
  private long state;
  private long savedState;
  private long nextState = 1;
  private LineEndingStats lineEndings;
  private LineEnding lineEnding;
  private TextEncoding encoding;
  private TextEncoding savedEncoding;
  private boolean readOnly;
  private boolean notifying;
  private int groupDepth;
  private UndoHistory.Step group;
  private SelectionModel groupOrigin;

  Document(
      CharSequence text,
      TextEncoding encoding,
      LineEnding empty,
      UndoLimits limits,
      LongSupplier clock) {
    this.storage = RopeStorage.of(text);
    this.history = new UndoHistory(limits);
    this.clock = clock;
    this.encoding = encoding;
    this.savedEncoding = encoding;
    this.lineEndings = LineEndingStats.count(text, 0, text.length());
    this.lineEnding = lineEndings.dominant(empty);
  }

  /**
   * Creates a document. Its line ending is the most frequent one in {@code text}, the first seen on
   * a tie, or {@code empty} when the text has no line break.
   *
   * @param text the initial text, which the undo history cannot go back past
   * @param encoding the encoding a save writes
   * @param empty the line ending of a text without breaks ({@code newFile.lineEnding})
   * @param limits the undo history bounds
   * @return the document, clean, at revision 0
   */
  public static Document of(
      CharSequence text, TextEncoding encoding, LineEnding empty, UndoLimits limits) {
    return new Document(text, encoding, empty, limits, System::nanoTime);
  }

  /**
   * Returns the current text: an immutable version, O(1).
   *
   * @return the text
   */
  public TextSnapshot text() {
    return storage.snapshot();
  }

  /**
   * Returns an immutable version of the document for a worker, O(1).
   *
   * @return the snapshot
   */
  public DocumentSnapshot snapshot() {
    return new DocumentSnapshot(revision, state, storage.snapshot(), encoding, lineEnding);
  }

  /**
   * Returns the revision: 0 when created, incremented by every change and never reused.
   *
   * @return the revision
   */
  public long revision() {
    return revision;
  }

  /**
   * Returns the position map from earlier revisions.
   *
   * @return the map, current with this document
   */
  public PositionMap positions() {
    return positions;
  }

  /**
   * Reports unsaved changes.
   *
   * @return true when the undo state or the encoding differ from the last save
   */
  public boolean isDirty() {
    return state != savedState || !encoding.equals(savedEncoding);
  }

  /**
   * Returns the line break counts, updated by every change.
   *
   * @return the counts; {@link LineEndingStats#isMixed} drives the status bar's "Mixed"
   */
  public LineEndingStats lineEndings() {
    return lineEndings;
  }

  /**
   * Returns the line ending Enter inserts and pasted breaks convert to.
   *
   * @return the line ending
   */
  public LineEnding lineEnding() {
    return lineEnding;
  }

  /**
   * Sets the line ending new breaks use; existing breaks are unchanged.
   *
   * @param ending the line ending
   */
  public void setLineEnding(LineEnding ending) {
    checkThread();
    lineEnding = Objects.requireNonNull(ending);
  }

  /**
   * Returns the encoding the next save writes.
   *
   * @return the encoding
   */
  public TextEncoding encoding() {
    return encoding;
  }

  /**
   * Sets the encoding the next save writes; the document is dirty until then.
   *
   * @param newEncoding the encoding
   */
  public void setEncoding(TextEncoding newEncoding) {
    checkThread();
    boolean wasDirty = isDirty();
    encoding = Objects.requireNonNull(newEncoding);
    fireDirty(wasDirty);
  }

  /**
   * Reports whether edits are refused.
   *
   * @return true for a read-only document
   */
  public boolean isReadOnly() {
    return readOnly;
  }

  /**
   * Refuses or allows edits, undo and redo.
   *
   * @param value true to refuse them
   */
  public void setReadOnly(boolean value) {
    checkThread();
    readOnly = value;
  }

  /**
   * Adds a listener.
   *
   * @param listener called after each change on the editing thread
   */
  public void addListener(DocumentListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added before
   */
  public void removeListener(DocumentListener listener) {
    listeners.remove(listener);
  }

  /**
   * Creates a selection model, a caret at offset 0, mapped through every change until disposed.
   *
   * @return the model
   */
  public SelectionModel newSelectionModel() {
    checkThread();
    SelectionModel model = new SelectionModel(this);
    models.add(model);
    return model;
  }

  /**
   * Creates an anchor, mapped through every change until disposed.
   *
   * @param offset an offset from 0 to the length
   * @param bias the side of an insert at the anchor it stays on
   * @return the anchor
   */
  public Anchor anchor(int offset, Bias bias) {
    checkThread();
    Objects.checkIndex(offset, storage.length() + 1);
    Anchor anchor = new Anchor(this, offset, bias);
    anchors.add(anchor);
    return anchor;
  }

  /**
   * Applies a batch as one change. The batch coalesces with the previous undo step when it
   * continues it ({@link EditBatch.Kind}), within {@value #COALESCE_NANOS} ns, with no caret move,
   * command or save in between; otherwise, or inside a group, it is a new step or joins the group.
   *
   * @param batch the replacements, in the current text's coordinates; an empty batch does nothing
   * @param origin the selection model that edited, whose selections the undo step records, or null
   * @param after the origin's selections after the edit; null maps them like every other model's
   * @throws IndexOutOfBoundsException when a replacement ends past the text
   * @throws IllegalStateException when read-only, called from a listener or another thread
   */
  public void apply(EditBatch batch, SelectionModel origin, SelectionSet after) {
    checkEditable();
    List<Replacement> replacements = batch.replacements();
    if (replacements.isEmpty()) {
      return;
    }
    int length = storage.length();
    Replacement last = replacements.get(replacements.size() - 1);
    Objects.checkFromToIndex(last.offset(), last.end(), length);
    List<Change> changes = new ArrayList<Change>(replacements.size());
    for (Replacement r : replacements) {
      changes.add(
          new Change(r.offset(), storage.subSequence(r.offset(), r.end()).toString(), r.text()));
    }
    SelectionSet before = origin != null ? origin.selections() : null;
    boolean wasDirty = isDirty();
    long now = clock.getAsLong();
    UndoHistory.Step top = history.top();
    List<Change> merged = null;
    if (groupDepth == 0
        && top != null
        && top.open
        && top.kind == batch.kind()
        && top.batches.size() == 1
        && top.stateAfter == state
        && state != savedState
        && now - top.nanos < COALESCE_NANOS) {
      merged = UndoHistory.merge(batch.kind(), top.batches.get(0), changes);
    }
    long newState = nextState++;
    if (merged != null) {
      history.replaceTop(merged);
      top.stateAfter = newState;
      top.nanos = now;
    } else if (groupDepth > 0) {
      group.add(changes);
    } else {
      breakCoalescing();
      UndoHistory.Step step = new UndoHistory.Step(batch.kind(), before, state);
      step.add(changes);
      step.stateAfter = newState;
      step.nanos = now;
      step.open = batch.kind() != EditBatch.Kind.COMMAND;
      history.push(step);
    }
    state = newState;
    change(changes, DocumentChange.Cause.EDIT, origin, after, wasDirty);
    if (groupDepth == 0) {
      history.top().after = origin != null ? origin.selections() : null;
    }
  }

  /**
   * Applies a batch with no selection model; no model's selections are recorded or set.
   *
   * @param batch the replacements
   */
  public void apply(EditBatch batch) {
    apply(batch, null, null);
  }

  /**
   * Opens a group: every batch until the matching {@link #endGroup} is one undo step. Groups nest;
   * the outermost one counts.
   *
   * @param origin the selection model whose selections the step records, or null
   */
  public void beginGroup(SelectionModel origin) {
    checkEditable();
    if (groupDepth++ == 0) {
      breakCoalescing();
      group =
          new UndoHistory.Step(
              EditBatch.Kind.COMMAND, origin != null ? origin.selections() : null, state);
      groupOrigin = origin;
    }
  }

  /** Closes a group; the outermost close records the step when the group applied anything. */
  public void endGroup() {
    checkThread();
    if (groupDepth == 0) {
      throw new IllegalStateException("no open group");
    }
    if (--groupDepth == 0) {
      UndoHistory.Step step = group;
      group = null;
      if (!step.batches.isEmpty()) {
        step.stateAfter = state;
        step.after = groupOrigin != null ? groupOrigin.selections() : null;
        history.push(step);
      }
      groupOrigin = null;
    }
  }

  /** Ends coalescing: the next typing starts a new undo step. */
  public void breakCoalescing() {
    UndoHistory.Step top = history.top();
    if (top != null) {
      top.open = false;
    }
  }

  /**
   * Reports whether an undo step exists.
   *
   * @return true when {@link #undo} changes the text
   */
  public boolean canUndo() {
    return history.canUndo() && groupDepth == 0;
  }

  /**
   * Reports whether a redo step exists.
   *
   * @return true when {@link #redo} changes the text
   */
  public boolean canRedo() {
    return history.canRedo() && groupDepth == 0;
  }

  /**
   * Undoes the newest step: one change per batch in the step, newest first.
   *
   * @param target the selection model that receives the step's selections from before it, or null
   * @return false when there was nothing to undo
   */
  public boolean undo(SelectionModel target) {
    checkEditable();
    if (!canUndo()) {
      return false;
    }
    boolean wasDirty = isDirty();
    UndoHistory.Step step = history.undo();
    step.open = false;
    for (int i = step.batches.size() - 1; i >= 0; i--) {
      if (i == 0) {
        state = step.stateBefore;
      }
      SelectionSet restore = i == 0 ? step.before : null;
      change(
          Change.inverse(step.batches.get(i)),
          DocumentChange.Cause.UNDO,
          restore != null ? target : null,
          restore,
          wasDirty);
    }
    return true;
  }

  /**
   * Redoes the step undone last: one change per batch in the step, oldest first.
   *
   * @param target the selection model that receives the step's selections from after it, or null
   * @return false when there was nothing to redo
   */
  public boolean redo(SelectionModel target) {
    checkEditable();
    if (!canRedo()) {
      return false;
    }
    boolean wasDirty = isDirty();
    UndoHistory.Step step = history.redo();
    int last = step.batches.size() - 1;
    for (int i = 0; i <= last; i++) {
      if (i == last) {
        state = step.stateAfter;
      }
      SelectionSet restore = i == last ? step.after : null;
      change(
          step.batches.get(i),
          DocumentChange.Cause.REDO,
          restore != null ? target : null,
          restore,
          wasDirty);
    }
    return true;
  }

  /**
   * Records a finished save of a snapshot: the document is clean while its undo state and encoding
   * match the snapshot's. Typing after the save starts a new undo step, so undo can return to it.
   *
   * @param saved the snapshot the save wrote
   */
  public void markSaved(DocumentSnapshot saved) {
    checkThread();
    boolean wasDirty = isDirty();
    savedState = saved.state();
    savedEncoding = saved.encoding();
    breakCoalescing();
    fireDirty(wasDirty);
  }

  /** Throws unless called on the editing thread, which the first call binds. */
  void checkThread() {
    Thread current = Thread.currentThread();
    if (owner == null) {
      owner = current;
    } else if (owner != current) {
      throw new IllegalStateException("document used from " + current.getName());
    }
  }

  void removeAnchor(Anchor anchor) {
    checkThread();
    anchors.remove(anchor);
  }

  void removeSelectionModel(SelectionModel model) {
    checkThread();
    models.remove(model);
    movedModels.remove(model);
  }

  /** Reports a model's new selections now, or after the text change being reported. */
  void selectionsChanged(SelectionModel model) {
    if (notifying) {
      movedModels.add(model);
    } else {
      model.fire();
    }
  }

  long undoBytes() {
    return history.bytes();
  }

  int undoSteps() {
    return history.steps();
  }

  private void checkEditable() {
    checkThread();
    if (notifying) {
      throw new IllegalStateException("edit from a listener");
    }
    if (readOnly) {
      throw new IllegalStateException("read-only document");
    }
  }

  /** Applies changes highest offset first, maps positions and reports the change. */
  private void change(
      List<Change> changes,
      DocumentChange.Cause cause,
      SelectionModel target,
      SelectionSet selections,
      boolean wasDirty) {
    TextSnapshot before = storage.snapshot();
    List<TextEdit> edits = new ArrayList<TextEdit>(changes.size());
    for (Change c : changes) {
      edits.add(
          new TextEdit(
              c.offset(),
              c.removed(),
              c.inserted(),
              before.lineAt(c.offset()),
              breaks(c.removed()),
              breaks(c.inserted())));
    }
    for (int i = changes.size() - 1; i >= 0; i--) {
      Change c = changes.get(i);
      int from = Math.max(0, c.offset() - 1);
      int to = Math.min(storage.length(), c.offset() + c.removed().length() + 1);
      LineEndingStats old = LineEndingStats.count(storage, from, to);
      storage.replace(c.offset(), c.removed().length(), c.inserted());
      int newTo = to + c.inserted().length() - c.removed().length();
      lineEndings = lineEndings.replace(old, LineEndingStats.count(storage, from, newTo));
    }
    revision++;
    ChangeMap map = new ChangeMap(changes);
    positions.add(revision, map);
    for (Anchor anchor : anchors) {
      anchor.map(map);
    }
    DocumentChange change = new DocumentChange(revision, cause, List.copyOf(edits));
    notifying = true;
    try {
      for (SelectionModel model : models) {
        if (model == target && selections != null) {
          model.restore(selections);
        } else {
          model.map(map, before);
        }
      }
      for (DocumentListener listener : new ArrayList<DocumentListener>(listeners)) {
        listener.textChanged(change);
      }
      for (SelectionModel model : new ArrayList<SelectionModel>(movedModels)) {
        model.fire();
      }
      fireDirty(wasDirty);
    } finally {
      movedModels.clear();
      notifying = false;
    }
  }

  private void fireDirty(boolean wasDirty) {
    boolean dirty = isDirty();
    if (dirty != wasDirty) {
      for (DocumentListener listener : new ArrayList<DocumentListener>(listeners)) {
        listener.dirtyChanged(dirty);
      }
    }
  }

  private static int breaks(String text) {
    LineEndingStats stats = LineEndingStats.count(text, 0, text.length());
    return (int) (stats.lf() + stats.crLf() + stats.cr());
  }
}
