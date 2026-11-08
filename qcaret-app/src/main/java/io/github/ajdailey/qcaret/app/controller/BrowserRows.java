package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.workspace.DirectoryListing;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The workspace browser's rows of one listing with their Size and Modified texts, built on the
 * listing's worker: the first format of a zoned time loads the time-zone rules.
 */
final class BrowserRows {
  private static final long KIB = 1024;

  private BrowserRows() {}

  /**
   * Returns a size as the Size column shows it.
   *
   * @param bytes the size
   * @param locale formats the number
   * @return bytes below 1 KiB, else KiB, MiB or GiB with one decimal
   */
  static String size(long bytes, Locale locale) {
    if (bytes < KIB) {
      return String.format(locale, "%d B", bytes);
    }
    String[] units = {"KiB", "MiB", "GiB"};
    double value = bytes / (double) KIB;
    int unit = 0;
    while (value >= KIB && unit < units.length - 1) {
      value /= KIB;
      unit++;
    }
    return String.format(locale, "%.1f %s", value, units[unit]);
  }

  /**
   * Returns the Modified column's format.
   *
   * @param locale the short date style's locale
   * @param zone the time zone shown
   * @return the formatter
   */
  static DateTimeFormatter dates(Locale locale, ZoneId zone) {
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).withZone(zone);
  }

  /**
   * Returns the Modified cell's tool tip format.
   *
   * @param locale the short date and time style's locale
   * @param zone the time zone shown
   * @return the formatter
   */
  static DateTimeFormatter times(Locale locale, ZoneId zone) {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone);
  }

  /**
   * Returns a listing's rows.
   *
   * @param dir the directory listed
   * @param listing its entries
   * @param locale formats sizes and times
   * @param zone the time zone shown
   * @return one row per entry, in the listing's order
   */
  @WorkerThread
  static List<WorkspaceNode> rows(Path dir, DirectoryListing listing, Locale locale, ZoneId zone) {
    DateTimeFormatter dates = dates(locale, zone);
    DateTimeFormatter times = times(locale, zone);
    List<DirectoryListing.Entry> entries = listing.entries();
    List<WorkspaceNode> rows = new ArrayList<WorkspaceNode>(entries.size());
    for (DirectoryListing.Entry entry : entries) {
      WorkspaceNode.Kind kind =
          switch (entry.kind()) {
            case DIRECTORY -> WorkspaceNode.Kind.DIRECTORY;
            case FILE -> WorkspaceNode.Kind.FILE;
            case CYCLE -> WorkspaceNode.Kind.CYCLE;
          };
      Instant modified = Instant.ofEpochMilli(entry.modified());
      rows.add(
          new WorkspaceNode(
              dir.resolve(entry.name()),
              entry.name(),
              kind,
              kind == WorkspaceNode.Kind.FILE ? size(entry.size(), locale) : "",
              dates.format(modified),
              times.format(modified)));
    }
    return rows;
  }
}
