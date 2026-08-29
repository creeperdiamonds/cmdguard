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
}
