package io.github.ajdailey.qcaret.core.concurrent;

/** How a background operation ended. */
public enum OperationOutcome {
  /** Ran to the end. */
  COMPLETED,
  /** Stopped by {@link OperationHandle#cancel}, by closing its source, or by closing workers. */
  CANCELED,
  /** Stopped by an I/O error or a refused memory reservation. */
  FAILED
}
