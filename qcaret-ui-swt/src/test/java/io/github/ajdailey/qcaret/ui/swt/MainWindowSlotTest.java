package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MainView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link MainWindowSlot} forwards every {@link MainView} method, a method added later included. */
@Tag("swt")
class MainWindowSlotTest {
  /** The busy calls the slot counts itself instead of forwarding. */
  private static final Set<String> COUNTED = Set.of("setActive", "setIdle");

  private Display display;
  private MainWindow window;

  @BeforeEach
  void open() {
    display = new Display();
    window = new MainWindow(display, Palette.LIGHT);
  }

  @AfterEach
  void close() {
    window.dispose();
    display.dispose();
  }

  @Test
  void everyMainViewMethodForwardsWithItsArguments() throws Exception {
    final List<Method> calls = new ArrayList<Method>();
    final List<Object[]> arguments = new ArrayList<Object[]>();
    MainView recorder =
        (MainView)
            Proxy.newProxyInstance(
                MainView.class.getClassLoader(),
                new Class<?>[] {MainView.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object proxy, Method method, Object[] args) {
                    calls.add(method);
                    arguments.add(args == null ? new Object[0] : args);
                    return value(method.getReturnType());
                  }
                });
    MainWindowSlot slot = new MainWindowSlot(window, recorder);
    int checked = 0;
    for (Method method : MainView.class.getMethods()) {
      calls.clear();
      arguments.clear();
      Object[] args = new Object[method.getParameterCount()];
      Class<?>[] types = method.getParameterTypes();
      for (int i = 0; i < args.length; i++) {
        args[i] = argument(types[i], i);
      }
      method.invoke(slot, args);
      if (COUNTED.contains(method.getName())) {
        assertTrue(calls.isEmpty(), method.getName());
        continue;
      }
      assertEquals(List.of(method), calls, method.toString());
      assertArrayEquals(args, arguments.get(0), method.toString());
      checked++;
    }
    assertEquals(MainView.class.getMethods().length - COUNTED.size(), checked);
  }

  @Test
  void theBusyCountStaysWithTheSlot() {
    final MainWindowSlot slot = new MainWindowSlot(window);
    slot.setActive();
    slot.setActive();
    slot.setIdle();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return slot.busy().active() == 1;
              }
            },
            Duration.ofSeconds(10)));
    assertEquals(0, window.busy().active());
  }

  /** A distinct value per position for the types {@link MainView} takes, null for the rest. */
  private static Object argument(Class<?> type, int index) {
    if (type == int.class) {
      return index + 7;
    } else if (type == boolean.class) {
      return Boolean.TRUE;
    } else if (type == String.class) {
      return "argument " + index;
    } else if (type == int[].class) {
      return new int[] {index, 3};
    }
    return null;
  }

  private static Object value(Class<?> type) {
    if (type == int.class) {
      return 0;
    } else if (type == boolean.class) {
      return Boolean.FALSE;
    }
    return null;
  }
}
