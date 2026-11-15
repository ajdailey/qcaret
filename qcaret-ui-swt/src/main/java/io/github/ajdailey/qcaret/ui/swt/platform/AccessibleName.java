package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Control;

/**
 * The name a screen reader reads for a control, settable after the control is built. Where {@link
 * PlatformTraits#labelsByDescription} holds (cocoa), the name is also the accessible description,
 * and {@link #attach} gives the control {@link ACC#ROLE_GROUP}, so VoiceOver reads the name instead
 * of {@code SWTComposite}; {@link #attachKeepingRole} leaves a control's own role. Elsewhere only
 * the name is set. Call on the UI thread.
 */
public final class AccessibleName extends AccessibleAdapter {
  /** Widget data key of the attached name. */
  private static final String KEY = "qcaret.accessibleName";

  /** Reports {@link ACC#ROLE_GROUP} for the control itself. */
  static final AccessibleControlAdapter GROUP_ROLE =
      new AccessibleControlAdapter() {
        @Override
        public void getRole(AccessibleControlEvent event) {
          if (event.childID == ACC.CHILDID_SELF) {
            event.detail = ACC.ROLE_GROUP;
          }
        }
      };

  private final boolean described;
  private final boolean grouped;
  private String name;

  private AccessibleName(String name, boolean described, boolean grouped) {
    this.name = name;
    this.described = described;
    this.grouped = grouped;
  }

  /**
   * Names a control that has no role of its own, such as a {@code Canvas}.
   *
   * @param control the control
   * @param name the name
   * @return the listener, through which the name changes
   */
  public static AccessibleName attach(Control control, String name) {
    boolean described = PlatformTraits.PLATFORM.labelsByDescription();
    return attach(control, name, described, described);
  }

  /**
   * Names a control that reports its own role, such as a {@code StyledText} or a painted button.
   *
   * @param control the control
   * @param name the name
   * @return the listener, through which the name changes
   */
  public static AccessibleName attachKeepingRole(Control control, String name) {
    return attach(control, name, PlatformTraits.PLATFORM.labelsByDescription(), false);
  }

  /**
   * Names a control, also as its description when {@code described}, with {@link ACC#ROLE_GROUP}
   * when {@code grouped}.
   *
   * @param control the control
   * @param name the name
   * @param described true to set the description
   * @param grouped true to report the group role
   * @return the listener, through which the name changes
   */
  static AccessibleName attach(Control control, String name, boolean described, boolean grouped) {
    AccessibleName listener = new AccessibleName(name, described, grouped);
    control.getAccessible().addAccessibleListener(listener);
    if (grouped) {
      control.getAccessible().addAccessibleControlListener(GROUP_ROLE);
    }
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
   * Reports whether the name is also the description.
   *
   * @return true where the platform labels by description
   */
  public boolean described() {
    return described;
  }

  /**
   * Reports whether the control reports {@link ACC#ROLE_GROUP} through this name.
   *
   * @return true for {@link #attach} where the platform labels by description
   */
  public boolean grouped() {
    return grouped;
  }

  @Override
  public void getName(AccessibleEvent event) {
    if (event.childID == ACC.CHILDID_SELF) {
      event.result = name;
    }
  }

  @Override
  public void getDescription(AccessibleEvent event) {
    if (described && event.childID == ACC.CHILDID_SELF) {
      event.result = name;
    }
  }
}
