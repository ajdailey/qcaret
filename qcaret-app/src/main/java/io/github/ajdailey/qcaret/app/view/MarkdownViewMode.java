package io.github.ajdailey.qcaret.app.view;

/** What a Markdown tab shows. A tab whose grammar is not {@code markdown} shows {@link #SOURCE}. */
public enum MarkdownViewMode {
  /** The editor alone. */
  SOURCE,
  /** The editor with the preview on its right, scrolling together. */
  SPLIT,
  /** The preview alone, read-only. */
  PREVIEW
}
