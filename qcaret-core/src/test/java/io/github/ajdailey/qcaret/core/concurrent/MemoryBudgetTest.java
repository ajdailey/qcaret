package io.github.ajdailey.qcaret.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryBudgetTest {
  @Test
  void reservationsStayWithinTheShare() {
    MemoryBudget budget = new MemoryBudget(1000, 60);
    assertEquals(600, budget.limit());
    assertTrue(budget.reserve(400));
    assertFalse(budget.reserve(201));
    assertEquals(400, budget.reserved());
    assertTrue(budget.reserve(200));
    assertEquals(0, budget.available());
    budget.release(600);
    assertEquals(600, budget.available());
  }
}
