package studios.creeperdiamonds.cmdguard.exposure;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The persisted half of the exposure layer: plain fields Gson can read and write, with no
 * Minecraft types, so the merge rules are testable without a game client.
 *
 * <p><b>Do not give this class (or {@code GuardConfig}) a constructor with arguments.</b>
 * Every field here is defaulted by its initializer, and those initializers only run because
 * Gson constructs this class through the implicit public no-arg constructor: {@code
 * ConstructorConstructor} prefers a declared no-arg constructor and falls back to {@code
 * Unsafe.allocateInstance} only when there is none. Declaring any constructor deletes the
 * implicit one, sends Gson down the {@code Unsafe} path, and then <em>no</em> initializer
 * runs -- every {@code boolean} in here silently loads as {@code false} and every set and map
 * as {@code null}. That is fail-open across the whole class at once: filtering off, and a
 * policy built from null sets. {@code ExposureSettingsTest}'s Gson round-trip test is the
 * guard; it asserts the flags come back on after a real parse, so it fails if anyone ever
 * does this.
 */
public final class ExposureSettings {

    public boolean enabled = true;

    /** Drop inbound payloads on withheld channels, so a mod never sees the probe. */
    public boolean filterInbound = true;

    /**
     * Force vanilla's empty login-query answer for withheld channels, so a mod with a
     * {@code ClientLoginNetworking} handler cannot answer a login probe and thereby disclose
     * itself. See {@code ConnectionMixin#cmdguard$forceVanillaLoginAnswer}.
     *
     * <p>A plain {@code boolean}, like {@link #enabled} and {@link #filterInbound}, and it
     * migrates correctly as one. This field was briefly a boxed {@code Boolean} on the theory
     * that Gson leaves an absent field null and a primitive would therefore load {@code false}
     * -- switching login filtering off for every config written before it existed. That theory
     * is wrong twice over, and was disproved by running a structurally identical class through
     * Gson 2.11.0 and 2.14.0 rather than reasoning about it: {@code
     * ReflectiveTypeAdapterFactory} assigns only fields the JSON actually names, so an absent
     * field is never written at all and keeps its initializer's value; and even a hand-edited
     * explicit {@code "filterLogin": null} is skipped, because that adapter drops a null for a
     * primitive field. Boxing was the only thing that could ever let this field reach null, so
     * it defended against a hazard it was itself creating. The real hazard is the constructor
     * one described on this class.
     */
    public boolean filterLogin = true;

    public Set<String> exposedNamespaces = new LinkedHashSet<>(ExposurePolicy.DEFAULT_NAMESPACES);
    public Set<String> exposedChannels = new LinkedHashSet<>();
    public Set<String> withheldChannels = new LinkedHashSet<>();

    /** Extra namespaces granted for one server address only. */
    public Map<String, Set<String>> perServerNamespaces = new LinkedHashMap<>();

    /** Builds the immutable snapshot a single connection uses for its whole lifetime. */
    public ExposurePolicy policyFor(String serverKey) {
        Set<String> namespaces = new LinkedHashSet<>(exposedNamespaces);
        Set<String> extra = perServerNamespaces.get(serverKey);
        if (extra != null) {
            namespaces.addAll(extra);
        }
        return new ExposurePolicy(namespaces, exposedChannels, withheldChannels);
    }

    /**
     * Lowercases everything, and repairs a config whose collections came back null.
     *
     * <p>The null repairs are for a <em>hand-edited</em> {@code null} in the JSON, not for an
     * absent field: Gson never assigns a field the JSON does not name, so an absent
     * {@code exposedChannels} still holds its initializer's empty set. But an explicit
     * {@code "exposedChannels": null} <em>is</em> written through for a reference field (only
     * primitives are skipped), and a null set reaching {@link #policyFor} is an NPE on the
     * netty path. An empty set, by contrast, is a deliberate strict-mode choice and is kept.
     */
    public void normalise() {
        if (exposedNamespaces == null) {
            exposedNamespaces = new LinkedHashSet<>(ExposurePolicy.DEFAULT_NAMESPACES);
        }
        if (exposedChannels == null) {
            exposedChannels = new LinkedHashSet<>();
        }
        if (withheldChannels == null) {
            withheldChannels = new LinkedHashSet<>();
        }
        if (perServerNamespaces == null) {
            perServerNamespaces = new LinkedHashMap<>();
        }

        exposedNamespaces = lower(exposedNamespaces);
        exposedChannels = lower(exposedChannels);
        withheldChannels = lower(withheldChannels);

        Map<String, Set<String>> repaired = new LinkedHashMap<>();
        perServerNamespaces.forEach((server, namespaces) -> {
            if (server != null && namespaces != null) {
                repaired.put(server.toLowerCase(Locale.ROOT), lower(namespaces));
            }
        });
        perServerNamespaces = repaired;
    }

    private static Set<String> lower(Set<String> input) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : input) {
            if (value != null) {
                out.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
