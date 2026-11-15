package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;

/**
 * Names the print job before {@code PrintDialog.open}, so the macOS print panel's PDF > Save as PDF
 * sheet proposes the document's name. SWT has no public call for it. Confined to the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaPrintJobNaming}</td></tr>
 *   <tr><td>win32, GTK</td><td>{@link #NONE}</td></tr>
 * </table>
 */
interface PrintJobNaming {
  /** Names nothing. */
  PrintJobNaming NONE =
      new PrintJobNaming() {
        @Override
        public void name(String document) {
          // Nothing to name.
        }
      };

  /** This platform's naming, chosen on first use. */
  PrintJobNaming PLATFORM = forPlatform();

  /**
   * Names the job the next {@code PrintDialog} opens with {@link #jobName} of the document. A
   * failure logs one WARN, names no later job and leaves printing working.
   *
   * @param document the document's name
   */
  void name(String document);

  /**
   * Returns the job name for a document: {@link PdfPrinter#fileName} without {@code .pdf}, since
   * the save sheet appends it.
   *
   * @param document the document's name
   * @return the job name
   */
  static String jobName(String document) {
    String pdf = PdfPrinter.fileName(document);
    return pdf.substring(0, pdf.length() - ".pdf".length());
  }

  /**
   * Returns the implementation for the running SWT.
   *
   * @return the naming; {@link #NONE} off cocoa
   */
  static PrintJobNaming forPlatform() {
    return "cocoa".equals(PlatformTraits.PLATFORM.name()) ? new CocoaPrintJobNaming() : NONE;
  }
}
