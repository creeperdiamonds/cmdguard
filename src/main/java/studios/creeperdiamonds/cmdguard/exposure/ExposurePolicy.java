package studios.creeperdiamonds.cmdguard.exposure;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether one channel may be disclosed to the connected server.
 *
 * <p>Immutable and free of Minecraft types, so it is testable without a game client and
 * safe to read from the netty event loop. Withholding is the default: anything this class
 * cannot confidently place is withheld, never exposed.
 */
public final class ExposurePolicy {

    /**
     * Exposed regardless of configuration.
     *
     * <p>{@code minecraft:brand} is the client's truthful identification and is never
     * touched. {@code c:version} is protocol negotiation carrying no mod data.
     * {@code fabric:registry/sync/complete} is a zero-byte acknowledgement -- verified
     * against Fabric API 0.141.6, its codec is {@code StreamCodec.unit} -- and stalling it
     * costs the player the join for no privacy gain.
     */
    public static final Set<String> NEVER_WITHHELD = Set.of(
            "minecraft:brand",
            "c:version",
            "fabric:registry/sync/complete");

    /** Generic namespaces every Fabric client has, which therefore distinguish nobody. */
    public static final Set<String> DEFAULT_NAMESPACES = Set.of("fabric", "minecraft", "c");

    private final Set<String> exposedNamespaces;
    private final Set<String> exposedChannels;
    private final Set<String> withheldChannels;

    public ExposurePolicy(Set<String> exposedNamespaces,
                          Set<String> exposedChannels,
                          Set<String> withheldChannels) {
        this.exposedNamespaces = lowercased(exposedNamespaces);
        this.exposedChannels = lowercased(exposedChannels);
        this.withheldChannels = lowercased(withheldChannels);
    }

    public boolean isExposed(String channelId) {
        if (channelId == null) {
            return false;
        }
        String id = channelId.toLowerCase(Locale.ROOT);

        if (NEVER_WITHHELD.contains(id)) {
            return true;
        }
        if (withheldChannels.contains(id)) {
            return false;
        }
        if (exposedChannels.contains(id)) {
            return true;
        }

        String namespace = namespaceOf(id);
        return namespace != null && exposedNamespaces.contains(namespace);
    }

    /**
     * True for an id shaped like {@code namespace:path}, both halves non-empty, exactly one
     * colon -- i.e. something that could actually be a channel and could actually match.
     *
     * <p>Used by {@code /cmdguard expose|withhold channel <id>} to refuse a malformed id
     * rather than storing it. Storing one is worse than a typo: {@link #isExposed} would
     * never match it, so {@code withhold channel} in particular would silently do nothing
     * while the user believes the channel is withheld -- a false sense of privacy, which is
     * the one direction this feature must never fail in.
     */
    public static boolean isWellFormedChannelId(String channelId) {
        return channelId != null
                && namespaceOf(channelId) != null
                && channelId.indexOf(':') == channelId.lastIndexOf(':');
    }

    /** Null for anything that is not exactly one non-empty namespace and one non-empty path. */
    static String namespaceOf(String channelId) {
        int colon = channelId.indexOf(':');
        if (colon <= 0 || colon == channelId.length() - 1) {
            return null;
        }
        return channelId.substring(0, colon);
    }

    private static Set<String> lowercased(Set<String> input) {
        return input.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
