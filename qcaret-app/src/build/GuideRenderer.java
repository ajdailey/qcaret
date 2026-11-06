import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Converts the user guide's markdown to one self-contained HTML page: each image becomes a {@code
 * data:} URI, each heading gets the id its GitHub anchor has, and a link that leaves the page fails
 * the build, so the page loads nothing. Run at build time with the source launcher: {@code java -cp
 * commonmark.jar:tables.jar GuideRenderer.java <guide.md> <guide.html>}.
 */
public final class GuideRenderer {
  private static final String STYLE =
      """
      :root { color-scheme: light dark; }
      body { font-family: sans-serif; line-height: 1.5; max-width: 60em; margin: 0 auto;
        padding: 1em 2em; color: #222; background: #fff; }
      a { color: #0b57b0; }
      code { font-family: monospace; background: #f0f0f0; padding: 0 0.2em; }
      pre code { display: block; padding: 0.6em; overflow-x: auto; }
      table { border-collapse: collapse; margin: 1em 0; }
      th, td { border: 1px solid #ccc; padding: 0.3em 0.6em; text-align: left; vertical-align: top; }
      img { max-width: 100%; border: 1px solid #ccc; }
      @media (prefers-color-scheme: dark) {
        body { color: #ddd; background: #1e1e1e; }
        a { color: #7fb3ff; }
        code { background: #333; }
        th, td, img { border-color: #555; }
      }
      """;

  private GuideRenderer() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: GuideRenderer <guide.md> <guide.html>");
    }
    Path source = Path.of(args[0]).toAbsolutePath().normalize();
    Path out = Path.of(args[1]);
    List<Extension> extensions = List.of(TablesExtension.create());
    Parser parser = Parser.builder().extensions(extensions).build();
    Node document = parser.parse(Files.readString(source, StandardCharsets.UTF_8));
    Inliner inliner = new Inliner(source.getParent());
    document.accept(inliner);
    if (inliner.failure == null) {
      for (String target : inliner.targets) {
        if (!inliner.ids.contains(target)) {
          inliner.failure = "a link names no heading: #" + target;
        }
      }
    }
    if (inliner.failure != null) {
      throw new IOException(source + ": " + inliner.failure);
    }
    String body = HtmlRenderer.builder().extensions(extensions).build().render(document);
    String page =
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>"
            + inliner.title
            + "</title>\n<style>\n"
            + STYLE
            + "</style>\n</head>\n<body>\n"
            + body
            + "</body>\n</html>\n";
    Files.createDirectories(out.toAbsolutePath().getParent());
    Files.writeString(out, page, StandardCharsets.UTF_8);
  }

  /** Inlines images, names headings and checks links. */
  private static final class Inliner extends AbstractVisitor {
    private final Path base;
    private final Map<String, Integer> slugs = new HashMap<String, Integer>();
    private final Set<String> ids = new HashSet<String>();
    private final List<String> targets = new ArrayList<String>();
    private String title = "QCaret";
    private String failure;

    Inliner(Path base) {
      this.base = base;
    }

    @Override
    public void visit(Heading heading) {
      String text = text(heading);
      if (heading.getLevel() == 1) {
        title = text.replace("&", "&amp;").replace("<", "&lt;");
      }
      String slug = slug(text);
      Integer seen = slugs.get(slug);
      slugs.put(slug, seen == null ? 1 : seen + 1);
      String id = seen == null ? slug : slug + "-" + seen;
      ids.add(id);
      // A plain HtmlInline: the HTML renderer picks a node's renderer by its exact class, so a
      // subclass renders as nothing.
      HtmlInline anchor = new HtmlInline();
      anchor.setLiteral("<a id=\"" + id + "\"></a>");
      heading.prependChild(anchor);
      visitChildren(heading);
    }

    @Override
    public void visit(Link link) {
      if (!link.getDestination().startsWith("#")) {
        failure = "a link leaves the page: " + link.getDestination();
      } else {
        targets.add(link.getDestination().substring(1));
      }
      visitChildren(link);
    }

    @Override
    public void visit(Image image) {
      String source = image.getDestination();
      Path file = base.resolve(source).normalize();
      if (source.contains(":") || !file.startsWith(base) || !source.endsWith(".png")) {
        failure = "an image is not a PNG beside the guide: " + source;
        return;
      }
      try {
        byte[] bytes = Files.readAllBytes(file);
        image.setDestination(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
      } catch (IOException e) {
        failure = "cannot read " + source + ": " + e.getMessage();
      }
    }

    private static String text(Node node) {
      StringBuilder text = new StringBuilder();
      for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
        if (child instanceof Text literal) {
          text.append(literal.getLiteral());
        } else if (child instanceof org.commonmark.node.Code code) {
          text.append(code.getLiteral());
        } else {
          text.append(text(child));
        }
      }
      return text.toString();
    }

    /** GitHub's anchor: lower case, punctuation other than {@code -} and {@code _} dropped. */
    private static String slug(String text) {
      StringBuilder slug = new StringBuilder();
      for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
        if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
          slug.append(c);
        } else if (c == ' ') {
          slug.append('-');
        }
      }
      return slug.toString();
    }
  }
}
