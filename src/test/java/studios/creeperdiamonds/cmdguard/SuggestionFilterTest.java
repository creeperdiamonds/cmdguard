package studios.creeperdiamonds.cmdguard;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab-completion decision, tested where it is Minecraft-free. What cannot be tested here
 * -- that {@code ConnectionMixin}'s {@code @Inject} binds to {@code Connection#sendPacket},
 * that a {@code ServerboundCommandSuggestionPacket} really passes through it, and that
 * cancelling leaves the suggestion popup empty rather than hanging -- is launch-time and
 * in-game behaviour. A green build says nothing about any of it.
 */
class SuggestionFilterTest {

    private static final Set<String> ALLOWLIST = Set.of("msg", "login", "help");

    private static boolean withholds(String text) {
        return SuggestionFilter.withholds(text, true, true, ALLOWLIST);
    }

    @Test
    void allowsCompletionOfAnAllowlistedRootsArguments() {
        assertFalse(withholds("/msg Ste"));
        assertFalse(withholds("/msg"));
    }

    @Test
    void withholdsANonAllowlistedRoot() {
        // The same command the typed-command guard blocks. Blocking it there while sending it
        // here would be the whole defect this filter exists to close.
        assertTrue(withholds("/somemod:debug dump"));
    }

    @Test
    void withholdsAPartialRootThatIsOnItsWayToAnAllowlistedOne() {
        // "/ms" is not "msg". The conservative reading is deliberate: a prefix rule would send
        // every prefix of an allowlisted root to the server, leaking the keystrokes it claims
        // to guard. The cost is that a command NAME cannot be completed against the server --
        // stated in the README, not left to be discovered.
        assertTrue(withholds("/ms"));
        assertTrue(withholds("/m"));
    }

    @Test
    void withholdsAnEmptyOrRootlessRequest() {
        // Deliberately stricter than CommandFilter#blocks, which lets an empty root through
        // because an empty command sends nothing. Here the text is on the wire either way, so
        // it is judged, and an empty root is not on any allowlist.
        assertTrue(withholds(""));
        assertTrue(withholds("/"));
        assertTrue(withholds(null));
    }

    /**
     * The same case, but arranged so that only the {@code if (root.isEmpty()) return true;}
     * branch can produce the answer.
     *
     * <p>{@link #withholdsAnEmptyOrRootlessRequest} above documents the intent and does not
     * discriminate: deleting that branch leaves {@code !allowlist.contains("")}, which is
     * already true for every realistic allowlist, so the test stays green over the deleted
     * code. Verified by actually deleting the branch and running the suite -- it passed. An
     * allowlist that <em>contains</em> the empty string is the one input where the branch and
     * the set lookup disagree, so it is what pins the branch: without it the request would be
     * sent.
     */
    @Test
    void anEmptyRootIsWithheldByItsOwnBranchNotByTheAllowlistLookup() {
        Set<String> allowsTheEmptyRoot = Set.of("", "msg");
        assertTrue(SuggestionFilter.withholds("", true, true, allowsTheEmptyRoot));
        assertTrue(SuggestionFilter.withholds("/", true, true, allowsTheEmptyRoot));
        assertTrue(SuggestionFilter.withholds(null, true, true, allowsTheEmptyRoot));
        assertFalse(SuggestionFilter.withholds("/msg Ste", true, true, allowsTheEmptyRoot),
                "the empty-root branch must not affect anything with a real root");
    }

    @Test
    void judgesTextWithNoLeadingSlashAsACommand() {
        // Chat suggestions never reach this packet at all -- CommandSuggestions#updateCommandInfo
        // serves them locally from getCustomTabSugggestions. Slashless text that DOES reach it
        // comes from a command block's edit box, where it is a command, and vanilla's own
        // handler treats it as one. So it is judged as one here too.
        assertFalse(withholds("msg Steve"));
        assertTrue(withholds("setblock ~ ~ ~ stone"));
    }

    @Test
    void isCaseInsensitiveLikeTheCommandGuard() {
        assertFalse(withholds("/MSG Steve"));
    }

    @Test
    void theMasterSwitchOffWithholdsNothing() {
        assertFalse(SuggestionFilter.withholds("/somemod:debug", false, true, ALLOWLIST));
    }

    @Test
    void theSuggestionToggleOffWithholdsNothing() {
        assertFalse(SuggestionFilter.withholds("/somemod:debug", true, false, ALLOWLIST));
    }

    @Test
    void bothSwitchesOffStillWithholdNothingEvenWithNothingToDecideOn() {
        assertFalse(SuggestionFilter.withholds(null, false, false, null),
                "the switches are checked before anything can fail, so 'off' is never "
                        + "confused with 'could not decide'");
    }

    /**
     * An intent record, not a discriminating test, and named so.
     *
     * <p>It pins the observable behaviour -- a null allowlist withholds -- and nothing
     * narrower. Verified by deleting {@code if (allowlist == null) return true;} and running
     * the suite: it stayed green, because the resulting {@code NullPointerException} out of
     * {@code allowlist.contains(root)} is caught by the {@code catch (RuntimeException)} that
     * makes this method fail closed, which returns {@code true} as well. Nothing observable
     * from outside {@code SuggestionFilter} separates the two -- it logs nothing and throws
     * nothing -- so there is no assertion that would distinguish them, and inventing one
     * would mean changing working, fail-closed code for the benefit of a test.
     *
     * <p>What it is still worth having: it is the standing statement that "no policy at all"
     * must not read as "allow", and it would fail immediately if the explicit branch <em>and</em>
     * the catch-all were both removed, or if either were ever changed to return {@code false}.
     */
    @Test
    void aMissingAllowlistWithholds() {
        assertTrue(SuggestionFilter.withholds("/msg Steve", true, true, null));
    }

    @Test
    void anAllowlistThatThrowsFailsClosed() {
        Set<String> hostile = new java.util.AbstractSet<>() {
            @Override
            public boolean contains(Object o) {
                throw new IllegalStateException("concurrent edit");
            }

            @Override
            public java.util.Iterator<String> iterator() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        assertTrue(SuggestionFilter.withholds("/msg Steve", true, true, hostile));
    }

    @Test
    void anEmptyAllowlistWithholdsEverything() {
        // Strict mode (/cmdguard clear). The command guard blocks everything not handled
        // locally; completions must not be the exception that keeps talking.
        assertTrue(SuggestionFilter.withholds("/msg Steve", true, true, Set.of()));
    }

    /**
     * The invariant that makes this a guard rather than a second policy: for any text with a
     * real root, the suggestion decision matches the command decision on the same allowlist.
     *
     * <p>The expectation comes from {@link CommandFilter#blocks} -- the command guard's own
     * code, the very function {@code OutboundGuard#shouldBlock} calls -- not from a hand-copy
     * of its rule. That distinction is the whole value of this test: it used to compute
     * {@code !ALLOWLIST.contains(root)} itself, which is the rule under test written out
     * twice, so it agreed with whatever either side happened to do and could not have caught
     * the divergence it is named for. Now a change to either filter's rule fails it.
     *
     * <p>The two deliberate divergences are outside the inputs below, and each has its own
     * test: an empty root (allowed as a command, withheld as a completion) and a root the
     * client dispatcher owns (exempt as a command, not exempt here) -- which is why
     * {@code clientOwnsRoot} is fixed to "no" for this comparison. {@code clicked} is false
     * because a completion request has no clicked counterpart at all.
     */
    @Test
    void theSuggestionDecisionAgreesWithTheCommandDecision() {
        for (String text : new String[]{
                "/msg Steve", "/login hunter2", "/help", "/somemod:debug", "/ms", "/spawn"}) {
            boolean commandWouldBeBlocked = CommandFilter.blocks(
                    text, false, true, true, ALLOWLIST, root -> false);
            org.junit.jupiter.api.Assertions.assertEquals(
                    commandWouldBeBlocked, withholds(text),
                    text + " must be judged identically as a command and as a completion");
        }
    }

    /**
     * ...and the guard against that comparison going vacuous. If both filters were ever
     * changed to the same wrong answer -- "allow everything", say -- the loop above would
     * still pass, because it only asks that they agree. These pin what they must agree
     * <em>on</em>, so the pair cannot drift together.
     */
    @Test
    void theAgreementIsOnRealDecisionsNotOnTwoConstants() {
        assertFalse(CommandFilter.blocks("/msg Steve", false, true, true, ALLOWLIST, root -> false));
        assertTrue(CommandFilter.blocks("/somemod:debug", false, true, true, ALLOWLIST, root -> false));
        assertFalse(withholds("/msg Steve"));
        assertTrue(withholds("/somemod:debug"));
    }

    /**
     * The two divergences, stated as a test rather than only as prose: on exactly these
     * inputs the two guards must <em>not</em> agree, and each divergence is in the strict
     * direction.
     */
    @Test
    void theTwoDivergencesFromTheCommandDecisionAreBothStricter() {
        // An empty command runs nothing and sends nothing; an empty completion request is
        // still text on the wire.
        assertFalse(CommandFilter.blocks("", false, true, true, ALLOWLIST, root -> false));
        assertTrue(withholds(""));

        // A client-dispatcher root never reaches the network when run. A completion request
        // for it does -- NOTES.md leak vector #3.
        assertFalse(CommandFilter.blocks(
                "/somemod:debug", false, true, true, ALLOWLIST, root -> true));
        assertTrue(withholds("/somemod:debug"));
    }
}
