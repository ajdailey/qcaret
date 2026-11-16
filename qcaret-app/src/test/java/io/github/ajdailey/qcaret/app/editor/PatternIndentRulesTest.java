package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@link PatternIndentRules} under its read limit. */
class PatternIndentRulesTest {
  /**
   * Backtracks through every split of a run of {@code a} (5.3 million reads on 20 chars), then
   * matches through the second branch.
   */
  private static final Pattern RUNAWAY = Pattern.compile("a*a*a*a*a*a*a*a*b|a{20}");

  private static final String RUN = "a".repeat(20);

  @Test
  void theRunawayMatchesOnlyPastTheReadLimit() {
    BoundedChars unbounded = new BoundedChars().reset(RUN, Long.MAX_VALUE);
    assertTrue(RUNAWAY.matcher(unbounded).find());
    assertTrue(unbounded.used() > BoundedChars.lineLimit(RUN), Long.toString(unbounded.used()));
  }

  @Test
  void aRuleThatReachesTheReadLimitReportsFalseAndTheNextCallMatches() {
    PatternIndentRules rules = new PatternIndentRules(RUNAWAY, RUNAWAY, RUNAWAY);
    assertFalse(rules.increase(RUN));
    assertTrue(rules.increase("ab"));
    assertFalse(rules.dedentNext(RUN));
    assertTrue(rules.dedentNext("ab"));
    assertFalse(rules.decrease(RUN));
    assertTrue(rules.decrease("ab"));
  }
}
