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

    /**
     * The migration that a primitive {@code boolean} would have got wrong. A config written
     * before {@code filterLogin} existed still has an {@code exposure} block, so {@code
     * GuardConfig} keeps it rather than replacing it wholesale -- and Gson would have left a
     * primitive field {@code false}, silently switching login filtering off for every existing
     * user. Boxed plus this repair is what makes the absent field default to on.
     */
    @Test
    void normaliseTurnsAnAbsentLoginToggleOnRatherThanOff() {
        ExposureSettings settings = new ExposureSettings();
        settings.filterLogin = null;

        settings.normalise();

        assertEquals(Boolean.TRUE, settings.filterLogin);
        assertTrue(settings.loginFilterEnabled());
    }

    @Test
    void loginFilterReadsAsOnWhenTheFieldWasNeverRepaired() {
        ExposureSettings settings = new ExposureSettings();
        settings.filterLogin = null;

        assertTrue(settings.loginFilterEnabled(),
                "an unmigrated or hand-edited null must never read as 'filtering off'");
    }

    @Test
    void normaliseKeepsADeliberateLoginOptOut() {
        ExposureSettings settings = new ExposureSettings();
        settings.filterLogin = Boolean.FALSE;

        settings.normalise();

        assertFalse(settings.loginFilterEnabled(), "an explicit false is a choice, not a gap");
    }

    @Test
    void freshSettingsFilterLoginQueries() {
        assertTrue(new ExposureSettings().loginFilterEnabled());
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
