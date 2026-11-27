import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Renders the installer and package images from the application icon SVG: {@code
 * hicolor/<n>x<n>/apps/qcaret.png} at each freedesktop size, and the NSIS bitmaps {@code
 * nsis/welcome.bmp} (164 x 314, the welcome and finish pages) and {@code nsis/header.bmp} (150 x 57,
 * the page header). Run at build time with the source launcher: {@code java -cp jsvg.jar
 * InstallerArt.java <qcaret.svg> <out dir>}. The images hold no text, so the output does not depend on
 * the host's fonts.
 */
public final class InstallerArt {
  private static final int[] HICOLOR_SIZES = {16, 22, 24, 32, 48, 64, 128, 256, 512};
  private static final Color WELCOME_BACKGROUND = new Color(0x0e, 0x1a, 0x27);

  private InstallerArt() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: InstallerArt <qcaret.svg> <out dir>");
    }
    SVGDocument icon;
    try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
      icon = new SVGLoader().load(in, null, LoaderContext.createDefault());
    }
    if (icon == null) {
      throw new IOException("unreadable SVG: " + args[0]);
    }
    Path out = Path.of(args[1]);
    for (int size : HICOLOR_SIZES) {
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
      draw(icon, image, null, 0, 0, size);
      Path dir = out.resolve("hicolor/" + size + "x" + size + "/apps");
      Files.createDirectories(dir);
      write(image, "png", dir.resolve("qcaret.png"));
    }
    Path nsis = out.resolve("nsis");
    Files.createDirectories(nsis);
    BufferedImage welcome = new BufferedImage(164, 314, BufferedImage.TYPE_INT_RGB);
    draw(icon, welcome, WELCOME_BACKGROUND, 18, 40, 128);
    write(welcome, "bmp", nsis.resolve("welcome.bmp"));
    BufferedImage header = new BufferedImage(150, 57, BufferedImage.TYPE_INT_RGB);
    draw(icon, header, Color.WHITE, 96, 4, 49);
    write(header, "bmp", nsis.resolve("header.bmp"));
  }

  /** Fills {@code image} with {@code background} unless null, then draws the icon at x, y. */
  private static void draw(
      SVGDocument icon, BufferedImage image, Color background, int x, int y, int size) {
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      if (background != null) {
        g.setColor(background);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
      }
      icon.render((Component) null, g, new ViewBox(x, y, size, size));
    } finally {
      g.dispose();
    }
  }

  private static void write(BufferedImage image, String format, Path file) throws IOException {
    if (!ImageIO.write(image, format, file.toFile())) {
      throw new IOException("no " + format + " writer for " + file);
    }
  }
}
