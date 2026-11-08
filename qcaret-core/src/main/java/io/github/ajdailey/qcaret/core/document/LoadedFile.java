package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.EncodingDetection;
import io.github.ajdailey.qcaret.core.text.FileSettings;

/**
 * A loaded file. When {@code roundTrips} is false, encoding the text gives other bytes than the
 * file holds (a stateful or non-canonical encoding); the caller opens the document read-only and
 * names the encoding, and the user picks another or accepts the conversion.
 *
 * @param document the document, clean at revision 0 and not yet bound to a thread
 * @param baseline the file's stamp before it was read and the SHA-256 of the bytes read
 * @param detection the encoding and the rule that chose it
 * @param binary true when the file is binary and was opened as such
 * @param roundTrips true when the text encodes back to the file's bytes
 * @param retainedBytes the heap the document holds while open: its text as loaded and the undo
 *     headroom
 * @param settings the settings the file's EditorConfig properties and modelines declare
 */
public record LoadedFile(
    Document document,
    FileBaseline baseline,
    EncodingDetection detection,
    boolean binary,
    boolean roundTrips,
    long retainedBytes,
    FileSettings settings) {
  /**
   * Creates a loaded file without declared settings.
   *
   * @param document the document
   * @param baseline the file's stamp and digest
   * @param detection the encoding and its rule
   * @param binary true for a binary file
   * @param roundTrips true when the text encodes back to the file's bytes
   * @param retainedBytes the heap the document holds while open
   */
  public LoadedFile(
      Document document,
      FileBaseline baseline,
      EncodingDetection detection,
      boolean binary,
      boolean roundTrips,
      long retainedBytes) {
    this(document, baseline, detection, binary, roundTrips, retainedBytes, FileSettings.NONE);
  }

  /**
   * Returns this file with declared settings.
   *
   * @param value the settings
   * @return the file
   */
  public LoadedFile withSettings(FileSettings value) {
    return new LoadedFile(document, baseline, detection, binary, roundTrips, retainedBytes, value);
  }

  /**
   * Returns the file's stamp before it was read, the first save's baseline.
   *
   * @return the stamp
   */
  public FileStamp stamp() {
    return baseline.stamp();
  }
}
