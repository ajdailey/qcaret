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
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Renders the installer and package images from the application icon SVG: {@code
 * hicolor/<n>x<n>/apps/qcaret.png} at each freedesktop size, and the NSIS bitmaps {@code
 * nsis/welcome-<scale>.bmp} (164 x 314 at 100%, the welcome and finish pages) and {@code
 * nsis/header-<scale>.bmp} (150 x 57 at 100%, the page header) at each Windows display scale in
 * percent, plus {@code nsis/art.nsh}, which defines the scales, the 100% sizes and the backgrounds
 * for setup. A size at scale s is {@code (size * s + 50) / 100}, the rounding setup uses. Run at
 * build time with the source launcher: {@code java -cp jsvg.jar InstallerArt.java <qcaret.svg> <out
 * dir>}. The images hold no text, so the output does not depend on the host's fonts.
 */
public final class InstallerArt {
  private static final int[] HICOLOR_SIZES = {16, 22, 24, 32, 48, 64, 128, 256, 512};
  private static final int[] SCALES = {100, 125, 150, 175, 200, 250, 300};
  private static final int WELCOME_WIDTH = 164;
  private static final int WELCOME_HEIGHT = 314;
  private static final int HEADER_WIDTH = 150;
  private static final int HEADER_HEIGHT = 57;
  private static final Color WELCOME_BACKGROUND = new Color(0x0e, 0x1a, 0x27);
  private static final Color HEADER_BACKGROUND = Color.WHITE;

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
    StringBuilder nsh = new StringBuilder();
    nsh.append("!define ART_WELCOME_WIDTH ").append(WELCOME_WIDTH).append('\n');
    nsh.append("!define ART_WELCOME_BACKGROUND ").append(hex(WELCOME_BACKGROUND)).append('\n');
    nsh.append("!define ART_HEADER_WIDTH ").append(HEADER_WIDTH).append('\n');
    nsh.append("!define ART_HEADER_BACKGROUND ").append(hex(HEADER_BACKGROUND)).append('\n');
    nsh.append("!macro ART_FOR_EACH_SCALE m arg\n");
    for (int scale : SCALES) {
      BufferedImage welcome =
          new BufferedImage(
              scaled(WELCOME_WIDTH, scale),
              scaled(WELCOME_HEIGHT, scale),
              BufferedImage.TYPE_INT_RGB);
      draw(icon, welcome, WELCOME_BACKGROUND, 18, 40, 128, scale);
      write(welcome, "bmp", nsis.resolve("welcome-" + scale + ".bmp"));
      BufferedImage header =
          new BufferedImage(
              scaled(HEADER_WIDTH, scale), scaled(HEADER_HEIGHT, scale), BufferedImage.TYPE_INT_RGB);
      draw(icon, header, HEADER_BACKGROUND, 96, 4, 49, scale);
      write(header, "bmp", nsis.resolve("header-" + scale + ".bmp"));
      nsh.append("  !insertmacro ${m} ${arg} ").append(scale).append('\n');
    }
    nsh.append("!macroend\n");
    Files.writeString(nsis.resolve("art.nsh"), nsh.toString());
  }

  private static int scaled(int size, int scale) {
    return (size * scale + 50) / 100;
  }

  private static String hex(Color color) {
    return String.format(Locale.ROOT, "%06X", color.getRGB() & 0xffffff);
  }

  /** Draws the icon placed at x, y with the given size at 100%, scaled to {@code scale} percent. */
  private static void draw(
      SVGDocument icon,
      BufferedImage image,
      Color background,
      int x,
      int y,
      int size,
      int scale) {
    float factor = scale / 100f;
    draw(icon, image, background, x * factor, y * factor, size * factor);
  }

  /** Fills {@code image} with {@code background} unless null, then draws the icon at x, y. */
  private static void draw(
      SVGDocument icon, BufferedImage image, Color background, float x, float y, float size) {
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
