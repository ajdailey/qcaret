package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The name, the description and the group role a screen reader reads for a control. */
@Tag("swt")
class AccessibleNameTest {
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private static String name(Canvas control, AccessibleName name) {
    AccessibleEvent event = new AccessibleEvent(control.getAccessible());
    event.childID = ACC.CHILDID_SELF;
    name.getName(event);
    return event.result;
  }

  private static String description(Canvas control, AccessibleName name) {
    AccessibleEvent event = new AccessibleEvent(control.getAccessible());
    event.childID = ACC.CHILDID_SELF;
    name.getDescription(event);
    return event.result;
  }

  @Test
  void aDescribedNameIsAlsoTheDescriptionAndAGroupedOneReportsTheGroupRole() {
    Canvas plain = new Canvas(shell, SWT.NONE);
    Canvas described = new Canvas(shell, SWT.NONE);
    AccessibleName plainName = AccessibleName.attach(plain, "mock view", false, false);
    AccessibleName describedName = AccessibleName.attach(described, "mock view", true, true);
    assertEquals("mock view", name(plain, plainName));
    assertNull(description(plain, plainName));
    assertEquals("mock view", name(described, describedName));
    assertEquals("mock view", description(described, describedName));
    describedName.set("mock other");
    assertEquals("mock other", name(described, describedName));
    assertEquals("mock other", description(described, describedName));
    AccessibleControlEvent role = new AccessibleControlEvent(described.getAccessible());
    role.childID = ACC.CHILDID_SELF;
    role.detail = -1;
    AccessibleName.GROUP_ROLE.getRole(role);
    assertEquals(ACC.ROLE_GROUP, role.detail);
    assertSame(plainName, AccessibleName.of(plain));
    assertSame(describedName, AccessibleName.of(described));
  }

  @Test
  void theRunningPlatformDecidesTheDescriptionAndAKeptRoleIsNeverGrouped() {
    boolean described = PlatformTraits.PLATFORM.labelsByDescription();
    Canvas grouped = new Canvas(shell, SWT.NONE);
    Canvas kept = new Canvas(shell, SWT.NONE);
    AccessibleName groupedName = AccessibleName.attach(grouped, "mock");
    AccessibleName keptName = AccessibleName.attachKeepingRole(kept, "mock");
    assertEquals(described, groupedName.described());
    assertEquals(described, groupedName.grouped());
    assertEquals(described, keptName.described());
    assertFalse(keptName.grouped());
    assertNull(AccessibleName.of(new Canvas(shell, SWT.NONE)));
    assertTrue(PlatformTraits.of("cocoa", false).labelsByDescription());
  }
}
