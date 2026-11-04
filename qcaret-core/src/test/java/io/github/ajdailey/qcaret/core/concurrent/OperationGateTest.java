package io.github.ajdailey.qcaret.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationGateTest {
  @Test
  void closeWaitsForAdmittedOperationsAndRefusesNewOnes() throws Exception {
    OperationGate gate = new OperationGate();
    assertTrue(gate.enter());
    assertTrue(gate.enter());
    assertEquals(2, gate.active());
    gate.close();
    assertTrue(gate.isClosed());
    assertFalse(gate.enter());
    assertFalse(gate.awaitIdle(0));
    gate.exit();
    assertFalse(gate.awaitIdle(0));
    gate.exit();
    assertTrue(gate.awaitIdle(0));
    assertEquals(0, gate.active());
    gate.close();
    assertTrue(gate.awaitIdle(0));
  }

  @Test
  void anIdleGateIsIdleOnceClosed() throws Exception {
    OperationGate gate = new OperationGate();
    assertTrue(gate.enter());
    gate.exit();
    assertFalse(gate.awaitIdle(0));
    gate.close();
    assertTrue(gate.awaitIdle(0));
  }
}
