package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link PrintJobNaming}: the job name, the implementation per platform, and on macOS the SWT
 * internals {@link CocoaPrintJobNaming} reflects on. A failing macOS test after an SWT upgrade
 * names the members to update.
 */
@Tag("swt")
class PrintJobNamingTest {
  private static final String COCOA = "org.eclipse.swt.internal.cocoa.";

  @Test
  void theJobNameIsThePdfNameWithoutItsExtension() {
    assertEquals("d1d", PrintJobNaming.jobName("d1d.txt"));
    assertEquals("archive.tar", PrintJobNaming.jobName("archive.tar.gz"));
    assertEquals("Makefile", PrintJobNaming.jobName("Makefile"));
    assertEquals(".bashrc", PrintJobNaming.jobName(".bashrc"));
  }

  @Test
  @DisabledOnOs(OS.MAC)
  void onlyCocoaNamesTheJob() {
    assertSame(PrintJobNaming.NONE, PrintJobNaming.forPlatform());
    PrintJobNaming.NONE.name("d1d.txt");
  }

  /**
   * The name lands in the shared print info and survives what {@code PrintDialog.open} does before
   * the panel: a copy, then {@code updateFromPMPrintSettings}.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaMembersResolveAndTheCopiedPrintInfoCarriesTheName() throws Exception {
    Display display = new Display();
    try {
      assertTrue(PrintJobNaming.forPlatform() instanceof CocoaPrintJobNaming);
      CocoaPrintJobNaming naming = new CocoaPrintJobNaming();
      assertTrue(naming.supported());
      naming.name("d1d.txt");
      assertTrue(naming.supported());
      assertEquals("d1d", copiedJobName());
    } finally {
      display.dispose();
    }
  }

  private static String copiedJobName() throws Exception {
    ClassLoader loader = Shell.class.getClassLoader();
    Class<?> os = Class.forName(COCOA + "OS", false, loader);
    Method selector = os.getMethod("sel_registerName", String.class);
    Method send = os.getMethod("objc_msgSend", long.class, long.class);
    Method sendOne = os.getMethod("objc_msgSend", long.class, long.class, long.class);
    Class<?> nsString = Class.forName(COCOA + "NSString", false, loader);
    Class<?> nsId = Class.forName(COCOA + "id", false, loader);
    Object shared =
        Class.forName(COCOA + "NSPrintInfo", false, loader)
            .getMethod("sharedPrintInfo")
            .invoke(null);
    long info = nsId.getField("id").getLong(shared);
    long copy = (long) send.invoke(null, info, selector.invoke(null, "copy"));
    try {
      send.invoke(null, copy, selector.invoke(null, "updateFromPMPrintSettings"));
      long settings = (long) send.invoke(null, copy, selector.invoke(null, "printSettings"));
      Object key =
          nsString.getMethod("stringWith", String.class).invoke(null, CocoaPrintJobNaming.KEY);
      long value =
          (long)
              sendOne.invoke(
                  null,
                  settings,
                  selector.invoke(null, "objectForKey:"),
                  nsId.getField("id").getLong(key));
      if (value == 0) {
        return null;
      }
      Object string = nsString.getConstructor(long.class).newInstance(value);
      return (String) nsString.getMethod("getString").invoke(string);
    } finally {
      send.invoke(null, copy, selector.invoke(null, "release"));
    }
  }
}
