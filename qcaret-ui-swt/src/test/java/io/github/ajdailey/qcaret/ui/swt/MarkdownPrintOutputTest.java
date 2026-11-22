package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File > Print Rendered Markdown through a real window and a real Printer to a PDF file, with the
 * dialog's answer supplied. Uses GTK's Print to File or a Windows printer named PDF; skips without
 * one. With {@code qcaret.rm.out} set, the first two pages are also written there as PNG files.
 */
@Tag("swt")
class MarkdownPrintOutputTest {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final String FILE_BACKEND = "GtkPrintBackendFile";
  private static final int TABLE_ROWS = 90;

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path pdf;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
    pdf = temp.resolve("out.pdf");
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private PrinterData requirePrinter() {
    for (PrinterData data : Printer.getPrinterList()) {
      boolean windowsPdf =
          "win32".equals(SWT.getPlatform()) && data.name.toLowerCase(Locale.ROOT).contains("pdf");
      if (FILE_BACKEND.equals(data.driver) || windowsPdf) {
        data.printToFile = true;
        data.fileName = pdf.toString();
        return data;
      }
    }
    assumeTrue(false, "no printer writes a file here");
    return null;
  }

  private void openMarkdown(String text) throws Exception {
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars");
    Path file = temp.resolve("sample.md");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().loading() == 0 && controller.documents().activeMarkdown();
          }
        },
        "a Markdown tab");
  }

  private void print(final PrinterData data) {
    window.setPrinterChooser(
        new Supplier<PrinterData>() {
          @Override
          public PrinterData get() {
            return data;
          }
        });
    controller.execute(CommandId.PRINT_MARKDOWN);
  }

  private int pages() {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      return document.getNumberOfPages();
    } catch (IOException e) {
      return -1;
    }
  }

  private void awaitOutput() {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !window.printBar().shown() && pages() > 0;
          }
        },
        "the PDF");
  }

  private String pageText(int page) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      stripper.setLineSeparator("\n");
      stripper.setPageEnd("\n");
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      return stripper.getText(document);
    }
  }

  /** Writes a small PNG for the fixture's image. */
  private void writeImage(Path file) {
    ImageData data = new ImageData(40, 20, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    for (int x = 0; x < 40; x++) {
      for (int y = 0; y < 20; y++) {
        data.setPixel(x, y, x < 20 ? 0xff0000 : 0x0000ff);
      }
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    loader.save(file.toString(), SWT.IMAGE_PNG);
  }

  private static String fixture() {
    StringBuilder out = new StringBuilder();
    out.append("# Mock Title\n\nFirst paragraph with **strong** and `code` text.\n\n");
    out.append("![mock image](mock.png)\n\n");
    out.append("- first item\n- second item\n\n");
    out.append("```java\nclass MockCode {}\n```\n\n");
    out.append("## Mock Table\n\n| Column A | Column B |\n|---|---:|\n");
    for (int i = 1; i <= TABLE_ROWS; i++) {
      out.append("| row ").append(i).append(" | ").append(i * 7).append(" |\n");
    }
    out.append("\nLast paragraph.\n");
    return out.toString();
  }

  @Test
  void aRenderedDocumentPrintsInOrderWithTheTableHeaderOnEachPage() throws Exception {
    PrinterData data = requirePrinter();
    writeImage(temp.resolve("mock.png"));
    openMarkdown(fixture());
    assertTrue(new CommandStates(controller).enabled(CommandId.PRINT_MARKDOWN));
    print(data);
    awaitOutput();
    int pages = pages();
    assertTrue(pages >= 2, "pages " + pages);
    StringBuilder all = new StringBuilder();
    for (int page = 1; page <= pages; page++) {
      String text = pageText(page);
      assertTrue(text.contains("sample.md"), text);
      assertTrue(text.contains(UiText.PRINT_PAGE.format(page, pages)), text);
      if (page > 1 && text.contains("row ")) {
        assertTrue(text.contains("Column A"), "header repeats on page " + page);
      }
      all.append(text);
    }
    String text = all.toString();
    assertFalse(text.contains("# Mock"), "the source does not print");
    String[] order = {
      "Mock Title", "First paragraph", "first item", "MockCode", "Mock Table", "row 1 ", "row 2 "
    };
    int at = -1;
    for (String expected : order) {
      int next = text.indexOf(expected, at + 1);
      assertTrue(next > at, expected + " after index " + at + " in " + text);
      at = next;
    }
    int previous = -1;
    for (int i = 1; i <= TABLE_ROWS; i++) {
      int row = text.indexOf("row " + i + " ");
      assertTrue(row > previous, "row " + i);
      previous = row;
    }
    assertTrue(text.indexOf("Last paragraph") > previous);
    assertTrue(pageText(2).contains("Column A"), pageText(2));
    writePages(Math.min(2, pages));
  }

  /** A table row wider than the page printed as one line, cut at the body's right edge. */
  @Test
  void aTableRowWiderThanThePageWrapsInsideItsColumns() throws Exception {
    PrinterData data = requirePrinter();
    openMarkdown(
        "| Key | Note | Last |\n|---|---|---|\n| k1 | STARTWORD "
            + "mock words ".repeat(40)
            + "ENDWORD | LASTCELL |\n");
    print(data);
    awaitOutput();
    String text = pageText(1);
    assertTrue(text.contains("ENDWORD") && text.contains("LASTCELL"), text);
    boolean found = false;
    for (String line : text.split("\n")) {
      if (line.contains("STARTWORD")) {
        found = true;
        assertFalse(line.contains("ENDWORD"), "the long cell wraps: " + line);
        assertTrue(line.contains("LASTCELL"), "the last cell starts on the row's top: " + line);
      }
    }
    assertTrue(found, text);
    writePages(1);
  }

  /** Writes page images where {@code qcaret.rm.out} names a directory. */
  private void writePages(int count) throws IOException {
    String dir = System.getProperty("qcaret.rm.out");
    if (dir == null || dir.isEmpty()) {
      return;
    }
    Path out = Files.createDirectories(Path.of(dir));
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      PDFRenderer renderer = new PDFRenderer(document);
      for (int page = 0; page < count; page++) {
        BufferedImage image = renderer.renderImageWithDPI(page, 96);
        ImageIO.write(image, "png", out.resolve("print-markdown-" + (page + 1) + ".png").toFile());
      }
    }
  }

  @Test
  void cancelFromTheRowLeavesNoPdf() throws Exception {
    PrinterData data = requirePrinter();
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 20000; i++) {
      big.append("Mock paragraph ").append(i).append(".\n\n");
    }
    openMarkdown(big.toString());
    print(data);
    assertTrue(window.printBar().shown());
    window.printBar().cancelButton().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !window.printBar().shown();
          }
        },
        "the row closes");
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return false;
          }
        },
        Duration.ofMillis(300));
    assertFalse(Files.exists(pdf) && Files.size(pdf) > 0);
  }

  @Test
  void printRenderedMarkdownIsDisabledOutsideAMarkdownTab() throws Exception {
    Path file = temp.resolve("plain.txt");
    Files.writeString(file, "# not Markdown\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().loading() == 0 && window.tabs().getItemCount() == 1;
          }
        },
        "opened");
    assertFalse(new CommandStates(controller).enabled(CommandId.PRINT_MARKDOWN));
  }
}
