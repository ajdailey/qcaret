package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;

/**
 * The name a screen reader reads for a control, settable after the control is built. The platform
 * labels the control through {@link AccessibleLabels}: on cocoa the name is also the accessible
 * description, and {@link #attach} gives the control the group role, so VoiceOver reads the name
 * instead of {@code SWTComposite}; {@link #attachKeepingRole} leaves a control's own role.
 * Elsewhere only the name is set. Call on the UI thread.
 */
public final class AccessibleName extends AccessibleAdapter {
  /** Widget data key of the attached name. */
  private static final String KEY = "qcaret.accessibleName";

  /** Answers the accessible description with the current name. */
  private final AccessibleListener description =
      new AccessibleAdapter() {
        @Override
        public void getDescription(AccessibleEvent event) {
          if (event.childID == ACC.CHILDID_SELF) {
            event.result = name;
          }
        }
      };

  private String name;

  private AccessibleName(String name) {
    this.name = name;
  }

  /**
   * Names a control that has no role of its own, such as a {@code Canvas}.
   *
   * @param control the control
   * @param name the name
   * @return the listener, through which the name changes
   */
  public static AccessibleName attach(Control control, String name) {
    AccessibleName listener = named(control, name);
    AccessibleLabels.PLATFORM.describe(control, listener.description);
    return listener;
  }

  /**
   * Names a control that reports its own role, such as a {@code StyledText} or a painted button.
   *
   * @param control the control
   * @param name the name
   * @return the listener, through which the name changes
   */
  public static AccessibleName attachKeepingRole(Control control, String name) {
    AccessibleName listener = named(control, name);
    AccessibleLabels.PLATFORM.describeKeepingRole(control, listener.description);
    return listener;
  }

  private static AccessibleName named(Control control, String name) {
    AccessibleName listener = new AccessibleName(name);
    control.getAccessible().addAccessibleListener(listener);
    control.setData(KEY, listener);
    return listener;
  }

  /**
   * Returns the name attached to a control.
   *
   * @param control the control
   * @return the listener, or null when none is attached
   */
  public static AccessibleName of(Control control) {
    return control.getData(KEY) instanceof AccessibleName name ? name : null;
  }

  /**
   * Changes the name.
   *
   * @param value the name
   */
  public void set(String value) {
    name = value;
  }

  /**
   * Returns the name.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  /**
   * Returns the listener that answers the accessible description with the name, which {@link
   * AccessibleLabels} adds where the platform reads the description.
   *
   * @return the listener
   */
  public AccessibleListener description() {
    return description;
  }

  @Override
  public void getName(AccessibleEvent event) {
    if (event.childID == ACC.CHILDID_SELF) {
      event.result = name;
    }
  }
}
