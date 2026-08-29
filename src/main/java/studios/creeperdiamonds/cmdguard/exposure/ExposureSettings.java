package studios.creeperdiamonds.cmdguard.exposure;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The persisted half of the exposure layer: plain fields Gson can read and write, with no
 * Minecraft types, so the merge rules are testable without a game client.
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
     * <p><b>Boxed on purpose, unlike {@link #filterInbound}.</b> Gson leaves an absent field
     * null, and a {@code boolean} field would take that null as {@code false} -- silently
     * switching this off for every user whose {@code cmdguard.json} was written before this
     * field existed, which is a fail-<em>open</em> migration and exactly the direction this
     * layer must never fail in. {@link #filterInbound} does not need the same treatment
     * because it shipped with the whole {@code exposure} object: a config predating it has no
     * {@code exposure} block at all, so {@code GuardConfig} replaces the whole thing with a
     * fresh, correctly-defaulted instance. A config predating <em>this</em> field does have an
     * {@code exposure} block, so only {@link #normalise()} can repair it. Read it through
     * {@link #loginFilterEnabled()} rather than unboxing the field.
     */
    public Boolean filterLogin = Boolean.TRUE;

    /** {@link #filterLogin}, with a null (unmigrated or hand-edited) value read as on. */
    public boolean loginFilterEnabled() {
        return filterLogin == null || filterLogin;
    }

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
     * Repairs a config written before this feature existed. Gson leaves absent fields
     * null; an empty set, by contrast, is a deliberate strict-mode choice and is kept.
     */
    public void normalise() {
        if (filterLogin == null) {
            filterLogin = Boolean.TRUE;
        }
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
