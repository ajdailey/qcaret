package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures rendered Markdown with a {@code TextLayout} on a {@link Printer} from a print worker, as
 * a rendered print job does. Uses a printer that writes a file where there is one (GTK's Print to
 * File, a Windows printer named PDF), else the default printer, with the job canceled; skips
 * without a printer. Run on each CI platform, this is the check that measuring off the UI thread
 * works there.
 */
@Tag("swt")
class PrintMeasurerTest {
  private static final String FILE_BACKEND = "GtkPrintBackendFile";

  @TempDir Path temp;

  private Display display;
  private Workers workers;
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
  }

  @AfterEach
  void close() {
    try {
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private PrinterData printer() {
    for (PrinterData data : Printer.getPrinterList()) {
      boolean windowsPdf =
          "win32".equals(SWT.getPlatform()) && data.name.toLowerCase(Locale.ROOT).contains("pdf");
      if (FILE_BACKEND.equals(data.driver) || windowsPdf) {
        data.printToFile = true;
        data.fileName = temp.resolve("measure.pdf").toString();
        return data;
      }
    }
    return Printer.getDefaultPrinterData();
  }

  @Test
  void aLineMeasuresAndWrapsOnAPrinterFromAPrintWorker() throws Exception {
    PrinterData data = printer();
    assumeTrue(data != null, "no printer here");
    String paragraph = "mock words ".repeat(200);
    RenderedMarkdown rendered =
        new MarkdownRenderer()
            .render(
                "# Mock heading\n\n" + paragraph + "\n\n```\ncode\n```\n",
                null,
                new CancellationToken());
    final MarkdownPreviewContent content =
        new MarkdownPreviewContent(
            rendered,
            List.of(new int[0]),
            MarkdownSpans.of(rendered, List.of(new int[0])),
            List.<MarkdownImageRequest>of());
    final String body = display.getSystemFont().getFontData()[0].getName();
    final int mock = rendered.text().indexOf("mock words");
    final Printer printer = new Printer(data);
    final int[][] rows = new int[3][];
    final int[] widths = new int[2];
    final AtomicReference<String> thread = new AtomicReference<String>();
    final CountDownLatch done = new CountDownLatch(1);
    try {
      assertTrue(
          workers.tryStart(
              Workers.Kind.PRINT,
              new Runnable() {
                @Override
                public void run() {
                  thread.set(Thread.currentThread().getName());
                  try {
                    assertTrue(printer.startJob("mock"));
                    GC gc = new GC(printer);
                    MarkdownPrinting printing =
                        new MarkdownPrinting(
                            printer,
                            gc,
                            new Rectangle(0, 0, printer.getDPI().x * 4, printer.getDPI().y * 8),
                            content,
                            body,
                            PlatformTraits.PLATFORM.monospaceFamily(),
                            true);
                    try {
                      rows[0] = printing.rows(0, null);
                      rows[1] = printing.rows(2, null);
                      rows[2] = printing.rows(4, null);
                      widths[0] = printing.width(mock, mock + 4, false);
                      widths[1] = printing.width(mock, mock + 4, true);
                    } finally {
                      printing.dispose();
                      gc.dispose();
                      printer.cancelJob();
                    }
                  } finally {
                    done.countDown();
                  }
                }
              }));
      assertTrue(done.await(60, TimeUnit.SECONDS));
    } finally {
      printer.dispose();
    }
    assertNull(failure.get());
    assertTrue(thread.get().startsWith("qcaret-print-"), thread.get());
    assertEquals(1, rows[0].length);
    assertTrue(rows[1].length > 1, "paragraph rows " + rows[1].length);
    assertEquals(1, rows[2].length);
    assertTrue(rows[0][0] > rows[1][0], "a heading row is taller than a paragraph row");
    assertTrue(widths[0] > 0 && widths[1] >= widths[0], widths[0] + " " + widths[1]);
  }
}
