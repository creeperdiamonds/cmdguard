package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExposurePolicyTest {

    private static ExposurePolicy defaults() {
        return new ExposurePolicy(ExposurePolicy.DEFAULT_NAMESPACES, Set.of(), Set.of());
    }

    @Test
    void exposesDefaultNamespaces() {
        ExposurePolicy policy = defaults();
        assertTrue(policy.isExposed("fabric:registry/sync"));
        assertTrue(policy.isExposed("minecraft:register"));
        assertTrue(policy.isExposed("c:register"));
    }

    @Test
    void withholdsThirdPartyNamespaces() {
        assertFalse(defaults().isExposed("somemod:handshake"));
    }

    @Test
    void withholdsAmbiguousIdentifiers() {
        ExposurePolicy policy = defaults();
        assertFalse(policy.isExposed("nocolon"));
        assertFalse(policy.isExposed(":leadingcolon"));
        assertFalse(policy.isExposed("trailingcolon:"));
        assertFalse(policy.isExposed(null));
    }

    @Test
    void brandIsExposedEvenWhenExplicitlyWithheld() {
        ExposurePolicy policy = new ExposurePolicy(Set.of(), Set.of(), Set.of("minecraft:brand"));
        assertTrue(policy.isExposed("minecraft:brand"));
        assertTrue(policy.isExposed("c:version"));
        assertTrue(policy.isExposed("fabric:registry/sync/complete"));
    }

    @Test
    void channelWithholdBeatsNamespaceGrant() {
        ExposurePolicy policy = new ExposurePolicy(
                Set.of("fabric"), Set.of(), Set.of("fabric:accepted_attachments_v1"));
        assertFalse(policy.isExposed("fabric:accepted_attachments_v1"));
        assertTrue(policy.isExposed("fabric:registry/sync"));
    }

    @Test
    void channelGrantExposesWithoutExposingNamespace() {
        ExposurePolicy policy = new ExposurePolicy(Set.of(), Set.of("somemod:handshake"), Set.of());
        assertTrue(policy.isExposed("somemod:handshake"));
        assertFalse(policy.isExposed("somemod:other"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(defaults().isExposed("MINECRAFT:Register"));
    }

    @Test
    void acceptsIdsThatCouldActuallyMatch() {
        assertTrue(ExposurePolicy.isWellFormedChannelId("somemod:handshake"));
        assertTrue(ExposurePolicy.isWellFormedChannelId("fabric:registry/sync"));
        assertTrue(ExposurePolicy.isWellFormedChannelId("some-mod_2.0:a/b.c-d_e"));
    }

    @Test
    void rejectsMalformedShapes() {
        assertFalse(ExposurePolicy.isWellFormedChannelId(null));
        assertFalse(ExposurePolicy.isWellFormedChannelId(""));
        assertFalse(ExposurePolicy.isWellFormedChannelId("somemod"), "no colon");
        assertFalse(ExposurePolicy.isWellFormedChannelId(":handshake"), "empty namespace");
        assertFalse(ExposurePolicy.isWellFormedChannelId("somemod:"), "empty path");
        assertFalse(ExposurePolicy.isWellFormedChannelId("a:b:c"), "two colons");
    }

    /**
     * The regression this test exists for. {@code /cmdguard withhold channel} takes a
     * {@code greedyString}, so the whole rest of the line arrives as one argument -- spaces
     * included. The shape-only check accepted every id below (one colon, both halves
     * non-empty), the command stored it and told the user it had been applied, and {@link
     * ExposurePolicy#isExposed} could never match it, because a real channel id is the
     * {@code toString()} of an {@code Identifier} and an {@code Identifier} accepts none of
     * these characters. A stored id that silently withholds nothing is a false assurance in
     * the privacy-critical direction, which is the one direction this feature must not fail
     * in.
     */
    @Test
    void rejectsIdentifierCharsetViolations() {
        assertFalse(ExposurePolicy.isWellFormedChannelId("my mod:hand shake"),
                "spaces in both halves -- the reported case");
        assertFalse(ExposurePolicy.isWellFormedChannelId("somemod:hand shake"), "space in path");
        assertFalse(ExposurePolicy.isWellFormedChannelId("some mod:handshake"), "space in namespace");
        assertFalse(ExposurePolicy.isWellFormedChannelId("somemod:hand#shake"), "'#' is not allowed");
        assertFalse(ExposurePolicy.isWellFormedChannelId("some/mod:handshake"),
                "'/' is allowed in a path but not in a namespace");
    }

    /**
     * Whatever {@link ExposurePolicy#isWellFormedChannelId} accepts must be an id the
     * matching path can actually see, so the two are pinned to each other rather than
     * merely both being asserted about.
     */
    @Test
    void anAcceptedIdCanActuallyBeMatched() {
        String id = "some-mod_2.0:a/b.c-d_e";
        assertTrue(ExposurePolicy.isWellFormedChannelId(id));
        assertFalse(new ExposurePolicy(Set.of(), Set.of(), Set.of(id)).isExposed(id),
                "an id the command accepts must be one a withhold entry can match");
    }
}
