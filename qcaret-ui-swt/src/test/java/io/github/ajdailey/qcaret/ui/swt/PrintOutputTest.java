package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.swt.SWT;
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
 * File > Print through a real window and a real Printer to a PDF file, with the dialog's answer
 * supplied. Only a printer writing a file is used: GTK's Print to File, or a Windows printer named
 * PDF. Skips when the system has neither; no test reaches a physical printer.
 */
@Tag("swt")
class PrintOutputTest {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final String FILE_BACKEND = "GtkPrintBackendFile";

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
            Keymap.defaults(SwtKeys.mac()),
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

  /** Returns a printer that writes {@link #pdf}, or null. */
  private PrinterData filePrinter() {
    for (PrinterData data : Printer.getPrinterList()) {
      boolean windowsPdf =
          "win32".equals(SWT.getPlatform()) && data.name.toLowerCase(Locale.ROOT).contains("pdf");
      if (FILE_BACKEND.equals(data.driver) || windowsPdf) {
        data.printToFile = true;
        data.fileName = pdf.toString();
        return data;
      }
    }
    return null;
  }

  private PrinterData requirePrinter() {
    PrinterData data = filePrinter();
    assumeTrue(data != null, "no printer writes a file here");
    return data;
  }

  private void openFile(String text) throws Exception {
    Path file = temp.resolve("sample.java");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
  }

  private void print(final PrinterData data) {
    window.setPrinterChooser(
        new Supplier<PrinterData>() {
          @Override
          public PrinterData get() {
            return data;
          }
        });
    controller.execute(CommandId.PRINT);
  }

  /** Waits for the job's end and the file the print system writes from the event loop. */
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

  private int pages() {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      return document.getNumberOfPages();
    } catch (IOException e) {
      return -1;
    }
  }

  private String pageText(int page) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      return stripper.getText(document);
    }
  }

  private static String numbered(int lines) {
    StringBuilder out = new StringBuilder();
    for (int i = 1; i <= lines; i++) {
      out.append("mock line ").append(i).append('\n');
    }
    return out.toString();
  }

  @Test
  void aDocumentPrintsToAPdfWithHeadersNumbersAndEveryLine() throws Exception {
    PrinterData data = requirePrinter();
    int lines = 200;
    openFile(numbered(lines));
    print(data);
    awaitOutput();
    int pages = pages();
    assertTrue(pages >= 2, "pages " + pages);
    assertTrue(pageText(1).contains("sample.java"), pageText(1));
    assertTrue(pageText(1).contains(UiText.PRINT_PAGE.format(1, pages)), pageText(1));
    assertTrue(pageText(pages).contains(UiText.PRINT_PAGE.format(pages, pages)));
    StringBuilder all = new StringBuilder();
    for (int page = 1; page <= pages; page++) {
      all.append(pageText(page));
    }
    for (int i = 1; i <= lines; i++) {
      assertTrue(all.indexOf("mock line " + i + "\n") >= 0, "line " + i);
    }
    assertEquals(lines, all.toString().split("mock line ", -1).length - 1);
  }

  @Test
  void aLongLineWrapsInsideThePageAndATabExpands() throws Exception {
    PrinterData data = requirePrinter();
    String tail = "x".repeat(300) + "END";
    openFile("\tindented\n" + tail + "\n");
    print(data);
    awaitOutput();
    String text = pageText(1);
    assertEquals(1, pages());
    assertTrue(text.contains("indented"), text);
    assertTrue(text.contains("END"), text);
    assertFalse(text.contains("\t"), text);
    assertTrue(text.split("\n").length >= 4, text);
  }

  @Test
  void aPageRangePrintsThosePagesUnderTheWholeCount() throws Exception {
    PrinterData data = requirePrinter();
    openFile(numbered(200));
    data.scope = PrinterData.PAGE_RANGE;
    data.startPage = 2;
    data.endPage = 2;
    print(data);
    awaitOutput();
    assertEquals(1, pages());
    String text = pageText(1);
    assertTrue(text.contains("Page 2 of"), text);
    assertFalse(text.contains("mock line 1\n"), text);
  }

  @Test
  void copiesAreLeftToThePrintSystem() throws Exception {
    PrinterData data = requirePrinter();
    openFile("one page\n");
    data.copyCount = 2;
    print(data);
    awaitOutput();
    assertEquals(1, pages());
  }

  @Test
  void theSelectionPrintsAlone() throws Exception {
    PrinterData data = requirePrinter();
    openFile("first\nsecond\nthird\n");
    controller
        .documents()
        .activeEditor()
        .selections()
        .set(SelectionSet.of(List.of(Selection.of(6, 12)), 0));
    data.scope = PrinterData.SELECTION;
    print(data);
    awaitOutput();
    String text = pageText(1);
    assertTrue(text.contains("second"), text);
    assertFalse(text.contains("third"), text);
    assertFalse(text.contains("first"), text);
  }

  @Test
  void theWindowStaysUsableAndTheRowClosesAfterAJob() throws Exception {
    PrinterData data = requirePrinter();
    openFile(numbered(3000));
    print(data);
    assertTrue(window.printBar().shown());
    assertFalse(display.isDisposed());
    controller.execute(CommandId.PRINT);
    awaitOutput();
    assertFalse(window.printBar().shown());
  }

  @Test
  void cancelFromTheRowLeavesNoPdf() throws Exception {
    PrinterData data = requirePrinter();
    openFile(numbered(20000));
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
}
