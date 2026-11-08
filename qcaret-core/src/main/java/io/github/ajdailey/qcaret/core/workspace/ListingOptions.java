package io.github.ajdailey.qcaret.core.workspace;

/**
 * How the workspace browser lists a directory. Immutable.
 *
 * @param filter the files listed; directories pass whatever it says
 * @param hidden true to list hidden entries; excluded directory names stay out
 * @param order the sort key within directories and within files
 * @param descending true to reverse the key; directories still come first
 */
public record ListingOptions(NameFilter filter, boolean hidden, Order order, boolean descending) {
  /** Every file, no hidden entries, by name. */
  public static final ListingOptions DEFAULTS =
      new ListingOptions(NameFilter.ALL, false, Order.NAME, false);

  /** A sort key; ties sort by name. */
  public enum Order {
    /** The name, ignoring case. */
    NAME,
    /** A file's size in bytes; directories sort by name. */
    SIZE,
    /** The last-modified time. */
    MODIFIED
  }

  /** Checks the components. */
  public ListingOptions {
    if (filter == null || order == null) {
      throw new IllegalArgumentException("filter and order are required");
    }
  }
}
