import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Renders each {@code name.svg} in a directory to {@code name-light-16.png}, {@code
 * name-light-32.png}, {@code name-dark-16.png} and {@code name-dark-32.png}. {@code currentColor}
 * becomes the variant's ink: dark ink for the light palette, light ink for the dark one. Run at
 * build time with the source launcher: {@code java -cp jsvg.jar IconRenderer.java <in> <out>}.
 */
public final class IconRenderer {
  private static final String LIGHT_INK = "#3a3a3a";
  private static final String DARK_INK = "#d4d4d4";
  private static final int[] SIZES = {16, 32};

  private IconRenderer() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: IconRenderer <svg dir> <png dir>");
    }
    Path in = Path.of(args[0]);
    Path out = Path.of(args[1]);
    Files.createDirectories(out);
    try (DirectoryStream<Path> files = Files.newDirectoryStream(in, "*.svg")) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        String base = name.substring(0, name.length() - ".svg".length());
        String svg = Files.readString(file, StandardCharsets.UTF_8);
        render(svg, LIGHT_INK, out, base + "-light");
        render(svg, DARK_INK, out, base + "-dark");
      }
    }
  }

  private static void render(String svg, String ink, Path out, String prefix) throws IOException {
    byte[] bytes = svg.replace("currentColor", ink).getBytes(StandardCharsets.UTF_8);
    SVGDocument document =
        new SVGLoader()
            .load(new ByteArrayInputStream(bytes), null, LoaderContext.createDefault());
    if (document == null) {
      throw new IOException("unreadable SVG: " + prefix);
    }
    for (int size : SIZES) {
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
            RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        document.render((Component) null, g, new ViewBox(0, 0, size, size));
      } finally {
        g.dispose();
      }
      ImageIO.write(image, "png", out.resolve(prefix + "-" + size + ".png").toFile());
    }
  }
}
