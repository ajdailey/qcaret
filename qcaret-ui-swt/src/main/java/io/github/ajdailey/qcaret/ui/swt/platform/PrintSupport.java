package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/**
 * Print differences, one implementation per platform, from {@link PlatformTraits#printSupport}: the
 * no-printer check and how File > Print chooses a printer. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>No printer</th><th>Chooser</th></tr>
 *   <tr><td>win32</td><td>{@link Win32PrintSupport}</td><td>empty list</td>
 *       <td>dialog, default scope</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaPrintSupport}</td><td>empty list</td>
 *       <td>job named, dialog</td></tr>
 *   <tr><td>GTK 3</td><td>{@link Gtk3PrintSupport}</td><td>never</td><td>dialog</td></tr>
 *   <tr><td>GTK 4</td><td>{@link Gtk4PrintSupport}</td><td>never</td><td>PDF file</td></tr>
 * </table>
 */
public interface PrintSupport {
  /** The running platform's print support. */
  PrintSupport PLATFORM = PlatformTraits.PLATFORM.printSupport();

  /**
   * Reports whether the system lists no printer. GTK lists printers only after every print backend
   * answers, and its dialog always offers Print to File, so there the answer is false.
   *
   * @return true when File > Print has nothing to print to
   */
  boolean noPrinter();

  /**
   * Chooses a printer. The {@code PrintDialog} opens with {@link PrinterData#SELECTION} chosen when
   * there is a selection, except on win32, whose Windows 11 dialog offers no Selection scope and
   * returns Selection while showing All pages. On cocoa the job takes {@code jobName} first, so
   * Save as PDF proposes it. On GTK 4, where SWT 3.135's {@code PrintDialog.open} throws {@code
   * UnsatisfiedLinkError}, {@code pdf} chooses instead.
   *
   * @param parent the owning shell
   * @param jobName the job name
   * @param selected true when the document has a non-empty selection
   * @param open opens the dialog and returns its result
   * @param pdf chooses a PDF file printer, or returns null when canceled
   * @return the printer data, or null when canceled
   */
  PrinterData choose(
      Shell parent,
      String jobName,
      boolean selected,
      Function<PrintDialog, PrinterData> open,
      Supplier<PrinterData> pdf);

  /**
   * Creates the dialog {@link #choose} opens, not yet open, on the scope {@link #choose} describes.
   *
   * @param parent the owning shell
   * @param selected true when the document has a non-empty selection
   * @return the dialog
   */
  PrintDialog dialog(Shell parent, boolean selected);
}
