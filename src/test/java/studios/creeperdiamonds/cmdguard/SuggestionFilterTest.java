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
        // Deliberately stricter than OutboundGuard#shouldBlock, which lets an empty root
        // through because an empty command sends nothing. Here the text is on the wire either
        // way, so it is judged, and an empty root is not on any allowlist.
        assertTrue(withholds(""));
        assertTrue(withholds("/"));
        assertTrue(withholds(null));
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

    @Test
    void aMissingAllowlistFailsClosed() {
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
     * The two divergences are both outside this set -- an empty root, and a root the client
     * dispatcher owns (which this filter cannot see and deliberately does not exempt).
     */
    @Test
    void theSuggestionDecisionAgreesWithTheCommandDecision() {
        for (String text : new String[]{
                "/msg Steve", "/login hunter2", "/help", "/somemod:debug", "/ms", "/spawn"}) {
            String root = CommandRoot.of(text);
            boolean commandWouldBeBlocked = !ALLOWLIST.contains(root);
            org.junit.jupiter.api.Assertions.assertEquals(
                    commandWouldBeBlocked, withholds(text),
                    text + " must be judged identically as a command and as a completion");
        }
    }
}
