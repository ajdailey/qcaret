package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;

/**
 * Labels a control a screen reader reads, one implementation per platform from {@link
 * PlatformTraits#accessibleLabels}. On cocoa ({@link CocoaAccessibleLabels}) the description
 * listener answers the accessible description, and a control without a role of its own reports the
 * group role: VoiceOver reads a {@code Canvas} without a role as {@code SWTComposite} with a blank
 * description and does not read the accessible name. Elsewhere ({@link NativeAccessibleLabels})
 * nothing is added. Call on the UI thread.
 */
public interface AccessibleLabels {
  /** The running platform's labels. */
  AccessibleLabels PLATFORM = PlatformTraits.PLATFORM.accessibleLabels();

  /**
   * Describes a control that has no role of its own, such as a {@code Canvas}.
   *
   * @param control the control
   * @param description answers the accessible description
   */
  void describe(Control control, AccessibleListener description);

  /**
   * Describes a control that reports its own role and must keep it: each view's {@code StyledText}
   * (text), the Markdown preview's text, the find fields and their history arrows, the Outline
   * tree, and the tab strip's New, tab list and Close buttons (push button).
   *
   * @param control the control
   * @param description answers the accessible description
   */
  void describeKeepingRole(Control control, AccessibleListener description);
}
