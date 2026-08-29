package studios.creeperdiamonds.cmdguard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The withhold log's dedupe rule, driven directly through
 * {@code OutboundGuard#recordSuggestionRoot}.
 *
 * <p>Only the decision "does this root get a line" is testable here: the line itself goes to
 * {@code CmdGuardClient.LOGGER}, and {@code shouldBlockSuggestion} reads {@code
 * GuardConfig.get()}, which needs a launched game. That is enough, because the defect this
 * pins was entirely in the decision -- the old rule was {@code Set#add}, which reports every
 * distinct root, and a root that grows by one character per keystroke is a distinct root
 * every time.
 */
class SuggestionLogDedupeTest {

    @BeforeEach
    void forgetPreviousRoots() {
        OutboundGuard.resetSuggestionLogForTest();
    }

    /**
     * The scenario the guard exists for: a server hangs {@code ASK_SERVER} directly under the
     * root, so every keystroke produces a longer root. Under the old per-root dedupe this was
     * one INFO line per character, spelling the withheld command name out in {@code
     * latest.log}. It must now be one line.
     */
    @Test
    void aRootThatGrowsWithEveryKeystrokeIsReportedOnce() {
        String typed = "somemod:debug";
        int reported = 0;
        for (int i = 1; i <= typed.length(); i++) {
            if (OutboundGuard.recordSuggestionRoot(typed.substring(0, i))) {
                reported++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, reported,
                "one line for the whole word, not one per character");
    }

    /** And the first line is still emitted -- a guard nobody can see is the silent-filter bug. */
    @Test
    void theFirstOccurrenceIsStillReported() {
        assertTrue(OutboundGuard.recordSuggestionRoot("s"));
    }

    /**
     * The ordinary case is unchanged: an {@code ASK_SERVER} node on an <em>argument</em> means
     * the root is already complete on the first request, so it is reported in full, exactly as
     * before -- which is what keeps {@code /cmdguard allow <root>} actionable.
     */
    @Test
    void aCompleteRootIsStillReportedInFull() {
        assertTrue(OutboundGuard.recordSuggestionRoot("somemod:debug"));
        assertFalse(OutboundGuard.recordSuggestionRoot("somemod:debug"),
                "the same complete root repeats for every character of its arguments");
    }

    /** Backspacing, or retyping the same command shorter: the other prefix direction. */
    @Test
    void aShorterRootAfterALongerOneIsAlsoSuppressed() {
        assertTrue(OutboundGuard.recordSuggestionRoot("somemod:debug"));
        assertFalse(OutboundGuard.recordSuggestionRoot("somemod:deb"));
        assertFalse(OutboundGuard.recordSuggestionRoot("s"));
    }

    /** Unrelated roots are still each worth a line. The rule is prefixes, not "one per session". */
    @Test
    void anUnrelatedRootIsStillReported() {
        assertTrue(OutboundGuard.recordSuggestionRoot("somemod:debug"));
        assertTrue(OutboundGuard.recordSuggestionRoot("othermod:dump"));
        assertTrue(OutboundGuard.recordSuggestionRoot("spawn"));
    }

    /**
     * The set keeps the shortest of each family, so growing along one command cannot grow the
     * set. If a suppressed longer root were added anyway, "s" here would already be in it and
     * the later unrelated check below would still hold -- but the set would grow by one entry
     * per keystroke, which is the bound this rule exists to hold.
     */
    @Test
    void suppressedRootsAreNotAddedSoTheSetStaysBoundedByFamilies() {
        assertTrue(OutboundGuard.recordSuggestionRoot("s"));
        for (String longer : new String[]{"so", "som", "some", "somemod:debug"}) {
            assertFalse(OutboundGuard.recordSuggestionRoot(longer));
        }
        // Re-adding the family's shortest entry must still be suppressed by that same entry,
        // which is only true if none of the above replaced or removed it.
        assertFalse(OutboundGuard.recordSuggestionRoot("s"));
    }
}
