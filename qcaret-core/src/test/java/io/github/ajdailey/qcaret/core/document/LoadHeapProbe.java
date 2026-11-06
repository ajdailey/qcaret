package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Child JVM of {@link LoadHeapTest}: writes a file as large as this heap's budget admits, loads it
 * and prints the outcome. Arguments: the file and {@code ascii}, {@code wide} or {@code late}.
 */
public final class LoadHeapProbe {
  private static final byte[] ASCII_LINE =
      "0123456789 mock text 0123456789 mock text 0123456789\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] WIDE_LINE =
      "0123456789 mock 中 text 0123456789 mock text 0123\n".getBytes(StandardCharsets.UTF_8);

  private LoadHeapProbe() {}

  private static void write(Path file, long size, boolean lateWide, boolean wide)
      throws IOException {
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
      long written = 0;
      while (written + WIDE_LINE.length <= size) {
        byte[] line = wide || (lateWide && written > (1 << 17)) ? WIDE_LINE : ASCII_LINE;
        out.write(line);
        written += line.length;
      }
    }
  }

  /**
   * Runs the probe.
   *
   * @param args the file and the kind of text
   * @throws IOException when the file cannot be written
   */
  public static void main(String[] args) throws IOException {
    Path file = Path.of(args[0]);
    String kind = args[1];
    MemoryBudget budget = MemoryBudget.fromRuntime();
    boolean wide = kind.equals("wide");
    // Retained is text plus as much undo headroom below 64 MiB: twice the text in this range.
    long size = budget.limit() / (wide ? 4 : 2) - 4096;
    write(file, size, kind.equals("late"), wide);
    LoadCost cost = DocumentFiles.estimate(file, UndoLimits.DEFAULTS);
    try (LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
      if (reservation == null) {
        System.out.println("UNREAD");
        return;
      }
      LoadedFile loaded =
          DocumentFiles.load(file, LoadOptions.DEFAULTS, reservation, new CancellationToken());
      System.out.println("LOADED " + loaded.document().text().length());
    } catch (LoadTooLargeException e) {
      System.out.println("REFUSED");
    } catch (OutOfMemoryError e) {
      System.out.println("OUT OF MEMORY");
    }
  }
}
