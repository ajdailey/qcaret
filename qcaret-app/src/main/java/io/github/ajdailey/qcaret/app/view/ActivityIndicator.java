package io.github.ajdailey.qcaret.app.view;

/**
 * Shows that background work is running, without blocking input. Both methods are callable from any
 * thread. Each {@link #setActive} is matched by one {@link #setIdle} when the operation ends,
 * whether it completes, is canceled or fails; the indicator shows while any operation is active.
 */
public interface ActivityIndicator {
  /** Reports that an operation started. */
  void setActive();

  /** Reports that an operation ended. */
  void setIdle();
}
