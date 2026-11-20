package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.markdown.LinkTarget;
import io.github.ajdailey.qcaret.core.markdown.MarkdownFootnote;
import io.github.ajdailey.qcaret.core.markdown.MarkdownLink;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.List;

/**
 * Follows a link of the preview: a click on Basic text by its rendered offset, a navigation the
 * Browser preview canceled by its location. Confined to the UI thread.
 */
final class MarkdownLinks {
  private MarkdownLinks() {}

  /** Scrolls the shown preview. */
  interface Scroller {
    /** Scrolls Basic content to a rendered line. */
    void toLine(int line);

    /** Scrolls Browser content to an element id. */
    void toAnchor(String id);
  }

  /**
   * Follows a click on Basic text: a footnote reference scrolls to its footnote, an anchor to its
   * heading, a local file opens in QCaret, anything else opens in the system browser.
   *
   * @param r the shown render
   * @param offset the rendered offset clicked
   * @param tab opens files and URLs
   * @param scroller scrolls the preview
   */
  static void clicked(RenderedMarkdown r, int offset, MarkdownPreview.Tab tab, Scroller scroller) {
    for (MarkdownFootnote.Reference ref : r.footnoteReferences()) {
      if (offset >= ref.start() && offset < ref.end()) {
        for (MarkdownFootnote note : r.footnotes()) {
          if (note.number() == ref.number()) {
            scroller.toLine(note.line());
          }
        }
        return;
      }
    }
    MarkdownLink link = linkAt(r.links(), offset);
    if (link == null) {
      return;
    }
    LinkTarget target = link.target();
    switch (target.kind()) {
      case ANCHOR -> {
        int line = target.anchor().isEmpty() ? 0 : r.headingLine(target.anchor());
        if (line >= 0) {
          scroller.toLine(line);
        }
      }
      case FILE -> {
        if (target.path() != null) {
          tab.openFile(target.path());
        }
      }
      default -> tab.openUrl(target.destination());
    }
  }

  /**
   * Follows a Browser navigation: {@code about:} with a fragment scrolls to that id, a {@code
   * file:} URL opens in QCaret, an {@code http}, {@code https}, {@code mailto} or {@code ftp} URL
   * opens in the system browser; anything else is dropped.
   *
   * @param location the location the browser was about to load
   * @param tab opens files and URLs
   * @param scroller scrolls the preview
   */
  static void followed(String location, MarkdownPreview.Tab tab, Scroller scroller) {
    if (location.regionMatches(true, 0, "about:", 0, 6)) {
      int hash = location.indexOf('#');
      if (hash >= 0) {
        scroller.toAnchor(LinkTarget.of(location.substring(hash), null).anchor());
      }
      return;
    }
    LinkTarget target = LinkTarget.of(location, null);
    if (target.kind() == LinkTarget.Kind.FILE) {
      if (target.path() != null) {
        tab.openFile(target.path());
      }
    } else if (target.kind() == LinkTarget.Kind.URL && webScheme(location)) {
      tab.openUrl(location);
    }
  }

  private static boolean webScheme(String url) {
    for (String scheme : new String[] {"http:", "https:", "mailto:", "ftp:"}) {
      if (url.regionMatches(true, 0, scheme, 0, scheme.length())) {
        return true;
      }
    }
    return false;
  }

  /** The innermost link holding an offset: the last one starting at or before it. */
  private static MarkdownLink linkAt(List<MarkdownLink> links, int offset) {
    MarkdownLink found = null;
    for (MarkdownLink link : links) {
      if (link.start() <= offset && offset < link.end()) {
        found = link;
      }
    }
    return found;
  }
}
