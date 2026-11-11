package io.github.ajdailey.qcaret.app.view;

/**
 * A checksum list dialog's controller as its view calls it: Tools > Generate from Files and Tools >
 * Verify Checksums. Called on the UI thread.
 */
public interface ChecksumInput {
  /** Asks for the files to hash, or the list to verify, and starts a new run. */
  void choose();

  /** Copies the shown lines to the clipboard. */
  void copy();

  /** Asks for a file and writes the shown lines to it. */
  void saveAs();

  /** Cancels a running run. */
  void cancel();

  /** Cancels a running run; the dialog has closed. */
  void closed();
}
