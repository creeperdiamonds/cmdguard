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
