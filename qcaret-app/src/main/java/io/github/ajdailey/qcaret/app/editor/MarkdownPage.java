package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.theme.TokenStyle;
import java.util.Locale;

/**
 * The page around a Markdown HTML fragment: a content security policy that allows only inline
 * styles and {@code data:} images, and a stylesheet resembling GitLab's rendered Markdown in a
 * palette's colors. The Browser preview and File > Export as HTML use the same page.
 */
public final class MarkdownPage {
  /** The page's content security policy: nothing loads but inline styles and data images. */
  public static final String POLICY =
      "default-src 'none'; style-src 'unsafe-inline'; img-src data:";

  private static final String SANS =
      "-apple-system, \"Segoe UI\", Roboto, \"Noto Sans\", Ubuntu, Cantarell, \"Helvetica Neue\","
          + " sans-serif";
  private static final String MONO =
      "Menlo, \"DejaVu Sans Mono\", \"Liberation Mono\", Consolas, \"Ubuntu Mono\", \"Courier"
          + " New\", monospace";

  private MarkdownPage() {}

  /**
   * Returns a page.
   *
   * @param title the page title, escaped here
   * @param body the HTML fragment
   * @param palette the colors
   * @return the page
   */
  public static String page(String title, String body, Palette palette) {
    String css = stylesheet(palette);
    StringBuilder out = new StringBuilder(body.length() + css.length() + 512);
    out.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
        .append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
        .append(POLICY)
        .append("\">\n<title>");
    escape(title, out);
    out.append("</title>\n<style>\n")
        .append(css)
        .append("</style>\n</head>\n<body>\n<article class=\"markdown-body\">\n")
        .append(body)
        .append("</article>\n</body>\n</html>\n");
    return out.toString();
  }

  /**
   * Returns the stylesheet in a palette's colors.
   *
   * @param p the palette
   * @return CSS
   */
  public static String stylesheet(Palette p) {
    String text = hex(p.text());
    String border = hex(p.lineNumbers());
    String shade = hex(p.gutter());
    String link = hex(p.style(TokenRole.LINK).foreground());
    StringBuilder css = new StringBuilder(4096);
    css.append(":root { color-scheme: ")
        .append(p.dark() ? "dark" : "light")
        .append("; }\n")
        .append("html, body { margin: 0; background: ")
        .append(hex(p.background()))
        .append("; color: ")
        .append(text)
        .append("; }\n")
        .append("body { font-family: ")
        .append(SANS)
        .append("; font-size: 14px; line-height: 1.5; }\n")
        .append(".markdown-body { max-width: 980px; padding: 16px 24px; overflow-wrap: break-word;")
        .append(" }\n")
        .append("h1, h2, h3, h4, h5, h6 { font-weight: 600; line-height: 1.25;")
        .append(" margin: 24px 0 16px; }\n")
        .append("h1 { font-size: 1.75em; } h2 { font-size: 1.5em; } h3 { font-size: 1.3em; }")
        .append(" h4 { font-size: 1.1em; } h5, h6 { font-size: 1em; }\n")
        .append("h1, h2 { padding-bottom: 0.3em; border-bottom: 1px solid ")
        .append(border)
        .append("; }\n")
        .append("p, blockquote, ul, ol, dl, table, pre, details, .markdown-alert")
        .append(" { margin: 0 0 16px; }\n")
        .append("a { color: ")
        .append(link)
        .append("; text-decoration: none; } a:hover { text-decoration: underline; }\n")
        .append("code, pre { font-family: ")
        .append(MONO)
        .append("; font-size: 90%; }\n")
        .append("code { background: ")
        .append(shade)
        .append("; border-radius: 4px; padding: 2px 4px; }\n")
        .append("pre { background: ")
        .append(shade)
        .append("; border: 1px solid ")
        .append(border)
        .append("; border-radius: 4px; padding: 8px 12px; overflow: auto; line-height: 1.45; }\n")
        .append("pre code { background: none; padding: 0; font-size: 100%; }\n")
        .append("blockquote { margin-left: 0; padding: 0 16px; border-left: 3px solid ")
        .append(border)
        .append("; }\n")
        .append("hr { border: 0; border-top: 1px solid ")
        .append(border)
        .append("; margin: 24px 0; }\n")
        .append("table { border-collapse: collapse; display: block; overflow: auto; }\n")
        .append("th, td { border: 1px solid ")
        .append(border)
        .append("; padding: 6px 13px; }\n")
        .append("th { background: ")
        .append(shade)
        .append("; font-weight: 600; }\n")
        .append("img { max-width: 100%; }\n")
        .append("li > input[type=checkbox] { margin: 0 0.4em 0 -1.3em; }\n")
        .append("li:has(> input[type=checkbox]) { list-style: none; }\n")
        .append(".markdown-alert { padding: 8px 16px; border-left: 4px solid ")
        .append(link)
        .append("; }\n")
        .append(".markdown-alert-title { font-weight: 600; margin-bottom: 4px; }\n");
    alert(css, "note", p.style(TokenRole.LINK));
    alert(css, "tip", p.style(TokenRole.TYPE));
    alert(css, "important", p.style(TokenRole.NUMBER));
    alert(css, "warning", p.style(TokenRole.STRING));
    alert(css, "caution", p.style(TokenRole.INVALID));
    css.append(".footnotes { font-size: 90%; border-top: 1px solid ")
        .append(border)
        .append("; margin-top: 24px; }\n")
        .append(".front-matter { border-style: dashed; }\n")
        .append(".image-missing { font-style: italic; }\n");
    for (TokenRole role : TokenRole.values()) {
      TokenStyle style = p.style(role);
      css.append(".tk-")
          .append(role.name().toLowerCase(Locale.ROOT))
          .append(" { color: ")
          .append(hex(style.foreground()));
      if (style.bold()) {
        css.append("; font-weight: 600");
      }
      if (style.italic()) {
        css.append("; font-style: italic");
      }
      if (style.underline()) {
        css.append("; text-decoration: underline");
      }
      css.append("; }\n");
    }
    return css.toString();
  }

  private static void alert(StringBuilder css, String kind, TokenStyle style) {
    String color = hex(style.foreground());
    css.append(".markdown-alert-")
        .append(kind)
        .append(" { border-left-color: ")
        .append(color)
        .append("; } .markdown-alert-")
        .append(kind)
        .append(" .markdown-alert-title { color: ")
        .append(color)
        .append("; }\n");
  }

  private static String hex(Rgb rgb) {
    return String.format(Locale.ROOT, "#%02x%02x%02x", rgb.red(), rgb.green(), rgb.blue());
  }

  private static void escape(String text, StringBuilder out) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        default -> out.append(c);
      }
    }
  }
}
