package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.custom.TextChangeListener;
import org.eclipse.swt.custom.TextChangedEvent;
import org.eclipse.swt.custom.TextChangingEvent;

/**
 * The {@link StyledTextContent} a {@code StyledText} reads: the display text of a {@link
 * DisplayProjection}, one widget line per display row. The model is the only authority. The widget
 * never changes the text itself: {@link #replaceTextRange}, which the widget calls directly only
 * for input method composition text, goes to {@link EditorInput#preeditFromView}, outside undo;
 * {@link #setText} goes to the {@link EditorInput} as an edit. The text changes when the document
 * reports it. Each projection change becomes one {@code textChanging} and {@code textChanged} pair.
 * The adapter never sends {@code textSet}: a loaded document gets a new adapter, which the widget
 * reads whole in {@code setContent}.
 */
final class ContentAdapter implements StyledTextContent, DisplayProjection.Listener {
  private final DisplayProjection projection;
  private final EditorInput input;
  private final List<TextChangeListener> listeners = new ArrayList<TextChangeListener>();
  private Runnable changing;
  private Runnable changed;

  ContentAdapter(DisplayProjection projection, EditorInput input) {
    this.projection = projection;
    this.input = input;
    projection.setListener(this);
  }

  /**
   * Sets what runs around each change: before the widget hears of it, and once it has the new text.
   */
  void onChange(Runnable before, Runnable after) {
    changing = before;
    changed = after;
  }

  /** Detaches from the projection. */
  void dispose() {
    projection.setListener(null);
  }

  @Override
  public void changing(DisplayProjection.DisplayChange change) {
    if (changing != null) {
      changing.run();
    }
    TextChangingEvent event = new TextChangingEvent(this);
    event.start = change.start();
    event.replaceCharCount = change.replaceChars();
    event.newCharCount = change.newChars();
    event.replaceLineCount = change.replaceRows();
    event.newLineCount = change.newRows();
    for (TextChangeListener listener : new ArrayList<TextChangeListener>(listeners)) {
      listener.textChanging(event);
    }
  }

  @Override
  public void changed(DisplayProjection.DisplayChange change) {
    TextChangedEvent event = new TextChangedEvent(this);
    for (TextChangeListener listener : new ArrayList<TextChangeListener>(listeners)) {
      listener.textChanged(event);
    }
    if (changed != null) {
      changed.run();
    }
  }

  @Override
  public void addTextChangeListener(TextChangeListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeTextChangeListener(TextChangeListener listener) {
    listeners.remove(listener);
  }

  @Override
  public int getCharCount() {
    return projection.length();
  }

  @Override
  public String getLine(int lineIndex) {
    return projection.rowText(lineIndex);
  }

  @Override
  public int getLineAtOffset(int offset) {
    return projection.rowAt(offset);
  }

  @Override
  public int getLineCount() {
    return projection.rowCount();
  }

  @Override
  public String getLineDelimiter() {
    return projection.document().lineEnding().text();
  }

  @Override
  public int getOffsetAtLine(int lineIndex) {
    return projection.rowStart(lineIndex);
  }

  @Override
  public String getTextRange(int start, int length) {
    return projection.textRange(start, length);
  }

  @Override
  public void replaceTextRange(int start, int replaceLength, String text) {
    input.preeditFromView(start, start + replaceLength, text);
  }

  @Override
  public void setText(String text) {
    input.replaceFromView(0, projection.length(), text);
  }
}
