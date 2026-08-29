package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login-phase decision, tested where it is Minecraft-free. What cannot be tested here --
 * that {@code ConnectionMixin}'s {@code @ModifyVariable} binds to {@code
 * Connection#channelRead0}, that Fabric API's handshake mixin then skips the substituted
 * packet, and that vanilla's null answer goes out -- is launch-time and in-game behaviour. A
 * green build says nothing about any of it.
 */
class LoginQueryFilterTest {

    private static final ExposurePolicy DEFAULTS =
            new ExposurePolicy(ExposurePolicy.DEFAULT_NAMESPACES, Set.of(), Set.of());

    @Test
    void withholdsAThirdPartyChannelByDefault() {
        assertTrue(LoginQueryFilter.withholds("somemod:handshake", true, true, DEFAULTS));
    }

    @Test
    void allowsAnExposedNamespace() {
        assertFalse(LoginQueryFilter.withholds("fabric:something", true, true, DEFAULTS));
    }

    @Test
    void allowsAChannelGrantedByName() {
        ExposurePolicy policy = new ExposurePolicy(
                ExposurePolicy.DEFAULT_NAMESPACES, Set.of("somemod:handshake"), Set.of());
        assertFalse(LoginQueryFilter.withholds("somemod:handshake", true, true, policy));
        assertTrue(LoginQueryFilter.withholds("somemod:other", true, true, policy),
                "a channel-level grant must not spill over to the rest of the namespace");
    }

    @Test
    void aChannelLevelWithholdBeatsANamespaceGrant() {
        ExposurePolicy policy = new ExposurePolicy(
                Set.of("somemod"), Set.of(), Set.of("somemod:handshake"));
        assertTrue(LoginQueryFilter.withholds("somemod:handshake", true, true, policy));
    }

    @Test
    void neverWithheldChannelsStayAnswerable() {
        for (String channel : ExposurePolicy.NEVER_WITHHELD) {
            assertFalse(LoginQueryFilter.withholds(channel, true, true, DEFAULTS),
                    channel + " must never be withheld, in any phase");
        }
    }

    @Test
    void anInactiveConnectionWithholdsNothing() {
        assertFalse(LoginQueryFilter.withholds("somemod:handshake", false, true, DEFAULTS));
    }

    @Test
    void theLoginToggleOffWithholdsNothing() {
        assertFalse(LoginQueryFilter.withholds("somemod:handshake", true, false, DEFAULTS));
    }

    @Test
    void bothSwitchesOffStillWithholdsNothingEvenForAnUnreadableChannel() {
        assertFalse(LoginQueryFilter.withholds(null, false, false, null),
                "the switches are checked before anything can fail, so 'off' is never "
                        + "confused with 'could not decide'");
    }

    @Test
    void anUnreadableChannelFailsClosed() {
        assertTrue(LoginQueryFilter.withholds(null, true, true, DEFAULTS));
        assertTrue(LoginQueryFilter.withholds("not a channel id", true, true, DEFAULTS));
        assertTrue(LoginQueryFilter.withholds("", true, true, DEFAULTS));
    }

    @Test
    void aMissingPolicyFailsClosed() {
        assertTrue(LoginQueryFilter.withholds("somemod:handshake", true, true, null));
    }

    /**
     * The invariant the spec calls "the advertisement must match the enforcement", carried
     * into the login phase: a channel this filter would refuse to answer during login is
     * exactly one the same policy withholds from a registration list later. One rule, three
     * phases -- otherwise the client would decline to answer a channel it goes on to
     * advertise, or vice versa.
     */
    @Test
    void theLoginDecisionAgreesWithTheRestOfTheLayer() {
        List<String> channels = List.of(
                "somemod:handshake", "fabric:registry/sync", "minecraft:brand",
                "c:version", "othermod:login", "minecraft:register");
        for (String channel : channels) {
            assertEquals(!DEFAULTS.isExposed(channel),
                    LoginQueryFilter.withholds(channel, true, true, DEFAULTS),
                    channel + " must be decided identically in the login phase and elsewhere");
        }
    }

    @Test
    void theRemedyCommandNamesTheNamespaceTheUserMustExpose() {
        assertEquals("somemod", LoginQueryFilter.remedyNamespace("somemod:handshake"));
        assertEquals("somemod", LoginQueryFilter.remedyNamespace("somemod:deep/path"));
        assertEquals("/cmdguard expose global somemod",
                LoginQueryFilter.remedyCommand("somemod:handshake", DEFAULTS));
    }

    /**
     * An id with no namespace must not be pasted into the command, because {@code /cmdguard
     * expose global nocolon} does not parse -- and a user handed a command that fails cannot
     * tell whether they mistyped it or the mod did. The placeholder is at least honest about
     * being a blank to fill in.
     */
    @Test
    void theRemedyCommandDegradesToSomethingPrintableForAMalformedId() {
        assertEquals("<namespace>", LoginQueryFilter.remedyNamespace(null));
        assertEquals("<namespace>", LoginQueryFilter.remedyNamespace("nocolon"));
        assertEquals("/cmdguard expose global <namespace>",
                LoginQueryFilter.remedyCommand("nocolon", DEFAULTS));
    }

    /**
     * The remedy has to be one that would actually work. {@code ExposurePolicy.isExposed}
     * checks the channel-level withhold list before any namespace grant, so for a channel the
     * user withheld by name the advertised {@code expose global <namespace>} provably changes
     * nothing: they would run it, watch it fail, and conclude the mod is broken -- which is
     * the outcome that text exists to prevent.
     */
    @Test
    void theRemedyNamesTheChannelCommandForAChannelWithheldByName() {
        ExposurePolicy policy = new ExposurePolicy(
                Set.of("somemod"), Set.of(), Set.of("somemod:handshake"));

        assertTrue(LoginQueryFilter.withholds("somemod:handshake", true, true, policy),
                "precondition: the namespace is granted and the channel is withheld anyway");
        assertEquals("/cmdguard expose channel somemod:handshake",
                LoginQueryFilter.remedyCommand("somemod:handshake", policy));
        assertEquals("/cmdguard expose global somemod",
                LoginQueryFilter.remedyCommand("somemod:other", policy),
                "a sibling channel in the same namespace is not withheld by name");
    }

    @Test
    void theRemedyStaysPrintableWithoutAPolicy() {
        assertEquals("/cmdguard expose global somemod",
                LoginQueryFilter.remedyCommand("somemod:handshake", null));
        assertEquals("/cmdguard expose global <namespace>",
                LoginQueryFilter.remedyCommand(null, null));
    }
}
