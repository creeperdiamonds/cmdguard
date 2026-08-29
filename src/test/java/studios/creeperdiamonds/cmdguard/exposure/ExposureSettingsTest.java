package studios.creeperdiamonds.cmdguard.exposure;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import studios.creeperdiamonds.cmdguard.GuardConfig;

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
    void normaliseReplacesHandEditedNulls() {
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
    void normaliseKeepsADeliberateLoginOptOut() {
        ExposureSettings settings = new ExposureSettings();
        settings.filterLogin = false;

        settings.normalise();

        assertFalse(settings.filterLogin, "an explicit false is a choice, not a gap");
    }

    @Test
    void freshSettingsFilterLoginQueries() {
        assertTrue(new ExposureSettings().filterLogin);
    }

    /**
     * The migration test that actually goes through Gson, which is the only kind that could
     * have caught what went wrong here.
     *
     * <p>Every other test in this class sets fields by hand, so all of them agreed with a
     * premise nothing had checked: that Gson leaves an absent field null, and that a primitive
     * {@code boolean} would therefore load {@code false} and silently switch filtering off for
     * every config written before the field existed. That is false. {@code
     * ReflectiveTypeAdapterFactory} assigns only the fields the JSON actually names, and it
     * constructs the object through the implicit public no-arg constructor, so every field
     * initializer runs and an unnamed field keeps its default. This test parses exactly the
     * shape the premise was about -- an {@code exposure} block that is present (so {@code
     * GuardConfig} keeps it rather than replacing it wholesale) but names none of the flags --
     * and asserts all three come back on.
     *
     * <p>It is also the standing guard for the hazard that <em>is</em> real: give this class or
     * {@code GuardConfig} a constructor with arguments and the implicit no-arg one disappears,
     * Gson falls back to {@code Unsafe.allocateInstance}, no initializer runs, and every
     * primitive here loads {@code false} -- fail-open across the whole class. This test fails
     * the moment that happens; a hand-built {@code new ExposureSettings()} never would.
     */
    @Test
    void anOldConfigParsedByGsonKeepsEveryFlagOn() {
        String oldConfig = """
                {
                  "enabled": true,
                  "allowClickedCommands": true,
                  "allowlist": ["login", "register"],
                  "exposure": {
                    "exposedNamespaces": ["fabric", "minecraft", "c"],
                    "exposedChannels": [],
                    "withheldChannels": [],
                    "perServerNamespaces": {}
                  }
                }
                """;

        ExposureSettings exposure = new Gson().fromJson(oldConfig, GuardConfig.class).exposure;

        assertTrue(exposure.enabled, "an absent exposure.enabled must load as on");
        assertTrue(exposure.filterInbound, "an absent filterInbound must load as on");
        assertTrue(exposure.filterLogin, "an absent filterLogin must load as on");
    }

    /**
     * The other half of the same guarantee: an explicit {@code null} against a primitive is
     * skipped too, so even a hand-edited config cannot switch a flag off by writing null.
     * Boxing the field was the only thing that could ever have let one reach null.
     */
    @Test
    void aHandEditedNullFlagIsSkippedRatherThanReadAsFalse() {
        String edited = """
                {"exposure": {"enabled": null, "filterInbound": null, "filterLogin": null}}
                """;

        ExposureSettings exposure = new Gson().fromJson(edited, GuardConfig.class).exposure;

        assertTrue(exposure.enabled);
        assertTrue(exposure.filterInbound);
        assertTrue(exposure.filterLogin);
    }

    /** A deliberate opt-out written in the file is still honoured through a real parse. */
    @Test
    void anExplicitFalseInTheFileIsHonoured() {
        String optedOut = """
                {"exposure": {"filterLogin": false}}
                """;

        assertFalse(new Gson().fromJson(optedOut, GuardConfig.class).exposure.filterLogin);
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
