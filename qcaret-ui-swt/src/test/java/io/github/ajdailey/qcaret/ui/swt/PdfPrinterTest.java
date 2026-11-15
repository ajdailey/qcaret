package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** S9-10: the PDF save dialog proposes the document's name, not a fixed one. */
class PdfPrinterTest {
  @Test
  void theDocumentNameTakesThePdfExtension() {
    assertEquals("k.pdf", PdfPrinter.fileName("k.java"));
    assertEquals("archive.tar.pdf", PdfPrinter.fileName("archive.tar.gz"));
    assertEquals("Makefile.pdf", PdfPrinter.fileName("Makefile"));
    assertEquals(".bashrc.pdf", PdfPrinter.fileName(".bashrc"));
    assertEquals("Untitled 1.pdf", PdfPrinter.fileName("Untitled 1"));
  }
}
