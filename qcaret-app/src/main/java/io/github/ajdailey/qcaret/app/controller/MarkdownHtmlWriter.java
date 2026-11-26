package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.MarkdownHtml;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.markdown.LinkTarget;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTree;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.image.attributes.ImageAttributesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.AttributeProviderContext;
import org.commonmark.renderer.html.AttributeProviderFactory;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/**
 * Writes a parsed document as an HTML fragment with commonmark's {@link HtmlRenderer} and the HTML
 * renderers of the parse's table, strikethrough, task list, footnote, alert and image attribute
 * extensions. Raw HTML stays as written; link and image URLs pass commonmark's sanitizer. Text,
 * code and the table of contents write each {@link ControlChars#boxed} char as {@link
 * ControlChars#visible} text, such as {@code <1B>}; attributes keep the char.
 *
 * <ul>
 *   <li>Headings carry their GitLab anchor as id; the first other block on each later source line
 *       carries {@code qcaret-line-<line>}.
 *   <li>Fenced code and front matter are colored by QCaret's grammars: {@code <span
 *       class="tk-<role>">}, the role a lower-case {@link TokenRole} name.
 *   <li>{@code >>>} is a {@code blockquote}; {@code [[_TOC_]]} a nested {@code ul} of links to the
 *       headings.
 *   <li>A local image of a known type up to {@link MarkdownImages#MAX_FILE_BYTES} is embedded as a
 *       {@code data:} URI while the embedded total stays within {@link #EMBED_BYTES}; a remote
 *       image is a link with its alt text; any other image is its alt text.
 *   <li>For the preview, a link to a local file carries {@link MarkdownLinks#previewFileUrl}; for
 *       export, links stay as written. A link whose URL the sanitizer refuses has no {@code href}.
 * </ul>
 */
final class MarkdownHtmlWriter {
  /** Most image bytes one document embeds. */
  static final long EMBED_BYTES = 64L * 1024 * 1024;

  /** The id prefix of a block that is not a heading. */
  static final String LINE_ID = "qcaret-line-";

  private final MarkdownTree tree;
  private final Path folder;
  private final boolean export;
  private final CancellationToken cancel;
  private final Map<Node, int[]> tokens = new IdentityHashMap<Node, int[]>();
  private final Map<Node, String> sources = new IdentityHashMap<Node, String>();
  private final Map<Node, String> embedded = new IdentityHashMap<Node, String>();
  private final List<Integer> lines = new ArrayList<Integer>();
  private final List<String> ids = new ArrayList<String>();
  private int lastLine = -1;
  private long embeddedBytes;

  private MarkdownHtmlWriter(
      MarkdownTree tree, Path folder, boolean export, CancellationToken cancel) {
    this.tree = tree;
    this.folder = folder;
    this.export = export;
    this.cancel = cancel;
  }

  /**
   * Writes the preview's HTML: local file links carry {@code file:} URLs.
   *
   * @param tree the parsed document
   * @param folder the document's folder, or null for an untitled document
   * @param grammars the grammars code is colored with, or null for plain code
   * @param maxLineChars a longer code line stays plain
   * @param cancel checked per code block, image and element
   * @return the fragment
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static MarkdownHtml preview(
      MarkdownTree tree,
      Path folder,
      GrammarSet grammars,
      int maxLineChars,
      CancellationToken cancel) {
    return new MarkdownHtmlWriter(tree, folder, false, cancel).write(grammars, maxLineChars);
  }

  /**
   * Writes HTML for a file: links stay as written.
   *
   * @param tree the parsed document
   * @param folder the document's folder, or null for an untitled document
   * @param grammars the grammars code is colored with, or null for plain code
   * @param maxLineChars a longer code line stays plain
   * @param cancel checked per code block, image and element
   * @return the fragment
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static MarkdownHtml export(
      MarkdownTree tree,
      Path folder,
      GrammarSet grammars,
      int maxLineChars,
      CancellationToken cancel) {
    return new MarkdownHtmlWriter(tree, folder, true, cancel).write(grammars, maxLineChars);
  }

  @WorkerThread
  private MarkdownHtml write(GrammarSet grammars, int maxLineChars) {
    prepare(tree.document(), grammars, maxLineChars);
    HtmlRenderer renderer =
        HtmlRenderer.builder()
            .sanitizeUrls(true)
            .extensions(
                List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                    FootnotesExtension.create(),
                    AlertsExtension.create(),
                    ImageAttributesExtension.create()))
            .attributeProviderFactory(
                new AttributeProviderFactory() {
                  @Override
                  public AttributeProvider create(AttributeProviderContext context) {
                    return new Attributes();
                  }
                })
            .nodeRendererFactory(
                new HtmlNodeRendererFactory() {
                  @Override
                  public NodeRenderer create(HtmlNodeRendererContext context) {
                    return new Blocks(context);
                  }
                })
            .build();
    String body = renderer.render(tree.document());
    int[] at = new int[lines.size()];
    for (int i = 0; i < at.length; i++) {
      at[i] = lines.get(i);
    }
    return new MarkdownHtml(body, at, ids);
  }

  /** Colors code and front matter and reads the images to embed, before rendering. */
  @WorkerThread
  private void prepare(Node node, GrammarSet grammars, int maxLineChars) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof FencedCodeBlock code) {
        cancel.check();
        tokens.put(
            code,
            MarkdownCodeTokens.ofText(
                code.getLiteral(),
                MarkdownTree.language(code.getInfo()),
                grammars,
                maxLineChars,
                cancel));
      } else if (child instanceof YamlFrontMatterBlock) {
        cancel.check();
        String text = tree.frontMatter(child);
        sources.put(child, text);
        tokens.put(child, MarkdownCodeTokens.ofText(text, "yaml", grammars, maxLineChars, cancel));
      } else if (child instanceof Image image) {
        cancel.check();
        String data = embed(LinkTarget.of(image.getDestination(), folder));
        if (data != null) {
          embedded.put(image, data);
        }
        prepare(child, grammars, maxLineChars);
      } else {
        prepare(child, grammars, maxLineChars);
      }
    }
  }

  /** Reads a local image as a {@code data:} URI, or returns null. */
  @WorkerThread
  private String embed(LinkTarget target) {
    if (target.kind() != LinkTarget.Kind.FILE || target.path() == null) {
      return null;
    }
    String type = mediaType(target.path());
    if (type == null) {
      return null;
    }
    try {
      long size = Files.size(target.path());
      if (size > MarkdownImages.MAX_FILE_BYTES || embeddedBytes + size > EMBED_BYTES) {
        return null;
      }
      byte[] bytes = Files.readAllBytes(target.path());
      embeddedBytes += bytes.length;
      return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    } catch (IOException | SecurityException e) {
      return null;
    }
  }

  /** Image file extensions and their media types. */
  private static final String[][] TYPES = {
    {".png", "image/png"},
    {".jpg", "image/jpeg"},
    {".jpeg", "image/jpeg"},
    {".gif", "image/gif"},
    {".bmp", "image/bmp"},
    {".webp", "image/webp"},
    {".svg", "image/svg+xml"},
    {".tif", "image/tiff"},
    {".tiff", "image/tiff"}
  };

  /** The media type of an image file by extension, any case, or null. */
  static String mediaType(Path path) {
    String name = String.valueOf(path.getFileName());
    for (String[] type : TYPES) {
      String ext = type[0];
      if (name.regionMatches(true, name.length() - ext.length(), ext, 0, ext.length())) {
        return type[1];
      }
    }
    return null;
  }

  /** The text of an image's description. */
  private static String altText(Node node) {
    StringBuilder out = new StringBuilder();
    appendText(node, out);
    return out.toString();
  }

  private static void appendText(Node node, StringBuilder out) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Text t) {
        out.append(t.getLiteral());
      } else if (child instanceof Code c) {
        out.append(c.getLiteral());
      } else if (child instanceof SoftLineBreak) {
        out.append(' ');
      } else {
        appendText(child, out);
      }
    }
  }

  /**
   * Returns text with each {@link ControlChars#boxed} char except a line break as its {@link
   * ControlChars#visible} text.
   *
   * @param text the text
   * @return {@code text} itself when it holds no such char, else a copy
   */
  static String visible(String text) {
    StringBuilder shown = null;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean box = c != '\n' && c != '\r' && ControlChars.boxed(c);
      if (box && shown == null) {
        shown = new StringBuilder(text.length() + 8);
        shown.append(text, 0, i);
      }
      if (box) {
        shown.append(ControlChars.visible(String.valueOf(c)));
      } else if (shown != null) {
        shown.append(c);
      }
    }
    return shown == null ? text : shown.toString();
  }

  /** Ids, link targets and the cancel check. */
  private final class Attributes implements AttributeProvider {
    @Override
    public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
      cancel.check();
      if (node instanceof Heading heading) {
        String anchor = tree.anchor(heading);
        attributes.put("id", anchor);
        mark(MarkdownTree.firstLine(heading), anchor);
      } else if (node instanceof Block && !(node instanceof Document)) {
        int line = MarkdownTree.firstLine(node);
        if (line > lastLine && !attributes.containsKey("id")) {
          attributes.put("id", LINE_ID + line);
          mark(line, LINE_ID + line);
        }
      } else if (node instanceof Link link) {
        String href = export ? null : previewHref(link.getDestination());
        if (href != null) {
          attributes.put("href", href);
        } else if ("".equals(attributes.get("href"))) {
          // The sanitizer empties a refused scheme; an empty href links to the page itself.
          attributes.remove("href");
        }
      }
    }

    private void mark(int line, String id) {
      if (line > lastLine) {
        lastLine = line;
        lines.add(line);
        ids.add(id);
      }
    }
  }

  /**
   * A link's preview target: a local file's {@link MarkdownLinks#previewFileUrl}; null keeps the
   * sanitized one.
   */
  private String previewHref(String destination) {
    LinkTarget target = LinkTarget.of(destination, folder);
    if (target.kind() == LinkTarget.Kind.FILE && target.path() != null) {
      return MarkdownLinks.previewFileUrl(target.path(), target.anchor());
    }
    return null;
  }

  /** Fenced code, front matter, images and GitLab's two blocks. */
  private final class Blocks implements NodeRenderer {
    private final HtmlNodeRendererContext context;
    private final HtmlWriter html;

    Blocks(HtmlNodeRendererContext context) {
      this.context = context;
      this.html = context.getWriter();
    }

    @Override
    public Set<Class<? extends Node>> getNodeTypes() {
      return Set.of(
          Text.class,
          Code.class,
          IndentedCodeBlock.class,
          FencedCodeBlock.class,
          YamlFrontMatterBlock.class,
          Image.class,
          MarkdownTree.MULTILINE_QUOTE,
          MarkdownTree.TOC);
    }

    @Override
    public void render(Node node) {
      if (node instanceof Text text) {
        text(text.getLiteral());
      } else if (node instanceof Code code) {
        html.tag("code", context.extendAttributes(node, "code", Map.of()));
        text(code.getLiteral());
        html.tag("/code");
      } else if (node instanceof IndentedCodeBlock code) {
        code(node, code.getLiteral(), null, null);
      } else if (node instanceof FencedCodeBlock code) {
        code(node, code.getLiteral(), MarkdownTree.language(code.getInfo()), null);
      } else if (node instanceof YamlFrontMatterBlock) {
        code(node, sources.get(node), "yaml", "front-matter");
      } else if (node instanceof Image image) {
        image(image);
      } else if (node.getClass() == MarkdownTree.TOC) {
        toc(node);
      } else {
        html.line();
        html.tag("blockquote", context.extendAttributes(node, "blockquote", Map.of()));
        html.line();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
          context.render(child);
        }
        html.line();
        html.tag("/blockquote");
        html.line();
      }
    }

    /** Writes text escaped, each control char as its visible text. */
    private void text(String text) {
      html.text(visible(text));
    }

    private void code(Node node, String text, String language, String preClass) {
      Map<String, String> pre = new LinkedHashMap<String, String>();
      if (preClass != null) {
        pre.put("class", preClass);
      }
      Map<String, String> code = new LinkedHashMap<String, String>();
      if (language != null) {
        code.put("class", "language-" + language);
      }
      html.line();
      html.tag("pre", context.extendAttributes(node, "pre", pre));
      html.tag("code", context.extendAttributes(node, "code", code));
      int[] t = tokens.get(node);
      int at = 0;
      for (int i = 0; t != null && i < t.length; i += 3) {
        int start = Math.max(t[i], at);
        int end = Math.min(t[i + 1], text.length());
        if (start >= end) {
          continue;
        }
        text(text.substring(at, start));
        html.tag(
            "span",
            Map.of("class", "tk-" + TokenRole.values()[t[i + 2]].name().toLowerCase(Locale.ROOT)));
        text(text.substring(start, end));
        html.tag("/span");
        at = end;
      }
      text(text.substring(at));
      html.tag("/code");
      html.tag("/pre");
      html.line();
    }

    private void image(Image image) {
      String alt = altText(image);
      String data = embedded.get(image);
      if (data != null) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("src", data);
        attrs.put("alt", alt);
        if (image.getTitle() != null) {
          attrs.put("title", image.getTitle());
        }
        html.tag("img", context.extendAttributes(image, "img", attrs), true);
        return;
      }
      String destination = image.getDestination();
      LinkTarget target = LinkTarget.of(destination, folder);
      String label = alt.isEmpty() ? destination : alt;
      if (target.kind() == LinkTarget.Kind.URL) {
        String href = context.urlSanitizer().sanitizeLinkUrl(destination);
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("href", href);
        attrs.put("class", "image-link");
        html.tag("a", attrs);
        text(label);
        html.tag("/a");
      } else {
        html.tag("span", Map.of("class", "image-missing"));
        text(label);
        html.tag("/span");
      }
    }

    private void toc(Node node) {
      List<MarkdownTree.Entry> headings = tree.headings();
      if (headings.isEmpty()) {
        return;
      }
      int top = 6;
      for (MarkdownTree.Entry e : headings) {
        top = Math.min(top, e.level());
      }
      html.line();
      html.tag("ul", context.extendAttributes(node, "ul", Map.of("class", "section-nav")));
      int depth = 1;
      boolean itemOpen = false;
      for (MarkdownTree.Entry e : headings) {
        int level = e.level() - top + 1;
        if (level > depth) {
          while (depth < level) {
            if (!itemOpen) {
              html.tag("li");
            }
            html.tag("ul");
            depth++;
            itemOpen = false;
          }
        } else {
          if (itemOpen) {
            html.tag("/li");
          }
          while (depth > level) {
            html.tag("/ul");
            html.tag("/li");
            depth--;
          }
        }
        html.tag("li");
        html.tag("a", Map.of("href", "#" + e.anchor()));
        text(e.text());
        html.tag("/a");
        itemOpen = true;
      }
      if (itemOpen) {
        html.tag("/li");
      }
      while (depth > 1) {
        html.tag("/ul");
        html.tag("/li");
        depth--;
      }
      html.tag("/ul");
      html.line();
    }
  }
}
