package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExposureSettingsTest {

    @Test
    void freshSettingsExposeOnlyTheDefaultNamespaces() {
        ExposurePolicy policy = new ExposureSettings().policyFor("play.example.net");
        assertTrue(policy.isExposed("fabric:registry/sync"));
        assertFalse(policy.isExposed("somemod:handshake"));
    }

    @Test
    void perServerGrantAppliesOnlyToThatServer() {
        ExposureSettings settings = new ExposureSettings();
        settings.perServerNamespaces.put("play.example.net",
                new LinkedHashSet<>(List.of("somemod")));

        assertTrue(settings.policyFor("play.example.net").isExposed("somemod:handshake"));
        assertFalse(settings.policyFor("other.example.net").isExposed("somemod:handshake"));
    }

    @Test
    void normaliseReplacesNullsLeftByAnOlderConfigFile() {
        ExposureSettings settings = new ExposureSettings();
        settings.exposedNamespaces = null;
        settings.exposedChannels = null;
        settings.withheldChannels = null;
        settings.perServerNamespaces = null;

        settings.normalise();

        assertEquals(ExposurePolicy.DEFAULT_NAMESPACES, settings.exposedNamespaces);
        assertEquals(Set.of(), settings.exposedChannels);
        assertEquals(Set.of(), settings.withheldChannels);
        assertTrue(settings.perServerNamespaces.isEmpty());
    }

    @Test
    void normaliseKeepsADeliberatelyEmptiedNamespaceSet() {
        ExposureSettings settings = new ExposureSettings();
        settings.exposedNamespaces = new LinkedHashSet<>();

        settings.normalise();

        assertTrue(settings.exposedNamespaces.isEmpty(),
                "an empty set is a strict-mode choice, not a missing field");
    }

    @Test
    void normaliseLowercasesEntries() {
        ExposureSettings settings = new ExposureSettings();
        settings.exposedNamespaces = new LinkedHashSet<>(List.of("SomeMod"));

        settings.normalise();

        assertTrue(settings.policyFor("any").isExposed("somemod:handshake"));
    }
}
