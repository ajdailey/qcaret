package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.core.text.ControlChars;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.custom.TextChangeListener;

/**
 * A read-only widget's content over another: {@link #getLine} gives {@link ControlChars#shown}
 * lines for layout and painting, every other method the original. The widget's copy and cut keys
 * and its selection clipboard put the original chars in every format ({@link OriginalCopy}).
 */
public final class ShownContent implements StyledTextContent {
  private final StyledTextContent original;
  private final OriginalCopy copy = new OriginalCopy();

  private ShownContent(StyledTextContent original) {
    this.original = original;
  }

  /**
   * Wraps a read-only widget's content and routes its copies.
   *
   * @param widget the widget
   * @return the content the widget now holds
   */
  public static ShownContent install(StyledText widget) {
    ShownContent shown = new ShownContent(widget.getContent());
    widget.setContent(shown);
    shown.copy.routeKeys(widget);
    shown.copy.routeSelectionClipboard(widget);
    return shown;
  }

  /**
   * Returns a line with its control chars.
   *
   * @param lineIndex the line
   * @return the original line
   */
  public String original(int lineIndex) {
    return original.getLine(lineIndex);
  }

  @Override
  public String getLine(int lineIndex) {
    String line = original.getLine(lineIndex);
    return copy.copying() ? line : ControlChars.shown(line);
  }

  @Override
  public void addTextChangeListener(TextChangeListener listener) {
    original.addTextChangeListener(listener);
  }

  @Override
  public void removeTextChangeListener(TextChangeListener listener) {
    original.removeTextChangeListener(listener);
  }

  @Override
  public int getCharCount() {
    return original.getCharCount();
  }

  @Override
  public int getLineAtOffset(int offset) {
    return original.getLineAtOffset(offset);
  }

  @Override
  public int getLineCount() {
    return original.getLineCount();
  }

  @Override
  public String getLineDelimiter() {
    return original.getLineDelimiter();
  }

  @Override
  public int getOffsetAtLine(int lineIndex) {
    return original.getOffsetAtLine(lineIndex);
  }

  @Override
  public String getTextRange(int start, int length) {
    return original.getTextRange(start, length);
  }

  @Override
  public void replaceTextRange(int start, int replaceLength, String text) {
    original.replaceTextRange(start, replaceLength, text);
  }

  @Override
  public void setText(String text) {
    original.setText(text);
  }
}
