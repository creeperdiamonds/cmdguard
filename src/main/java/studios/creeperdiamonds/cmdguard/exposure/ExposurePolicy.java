package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether one channel may be disclosed to the connected server.
 *
 * <p>Immutable, and the decision path -- {@link #isExposed} and everything it touches --
 * works on {@code String} alone, so it is testable without a game client and safe to run on
 * the netty event loop. Withholding is the default: anything this class cannot confidently
 * place is withheld, never exposed.
 *
 * <p>The one exception is {@link #isWellFormedChannelId}, which deliberately calls {@code
 * Identifier.tryParse} -- see its Javadoc for why re-implementing the identifier charset
 * here would have been the wrong kind of purity. It is a command-thread validator, never
 * called from the filtering path.
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
     * True for an id that could actually be a channel and could actually match: shaped like
     * {@code namespace:path}, both halves non-empty, exactly one colon, <em>and</em> made
     * only of characters the game's own {@code Identifier} accepts.
     *
     * <p>Used by {@code /cmdguard expose|withhold channel <id>} to refuse a malformed id
     * rather than storing it. Storing one is worse than a typo: {@link #isExposed} would
     * never match it, so {@code withhold channel} in particular would silently do nothing
     * while the user believes the channel is withheld -- a false sense of privacy, which is
     * the one direction this feature must never fail in.
     *
     * <p><b>The shape checks alone were that exact failure.</b> The command argument is a
     * {@code greedyString}, so {@code /cmdguard withhold channel my mod:hand shake} arrives
     * here as one string with a space in each half. One colon, both halves non-empty: the
     * old check passed it, the config stored it, the user was told it had been applied, and
     * it could never match anything, because a real channel id is always the {@code
     * toString()} of an {@code Identifier} and an {@code Identifier} cannot contain a space.
     *
     * <p>{@code Identifier.tryParse} is the fix, and it is used rather than a local charset
     * test on purpose: the accepted set ({@code [a-z0-9_.-]} for the namespace, plus
     * {@code /} for the path) is the game's to define, and a copy of it here would be a
     * second source of truth that can silently drift from the class that actually parses
     * every channel id on the wire. {@code tryParse} returns null instead of throwing, so
     * nothing here has to catch.
     *
     * <p>{@code tryParse} on its own is not sufficient, which is why the shape checks stay:
     * verified against the decompiled 1.21.11 {@code Identifier}, it defaults a missing
     * namespace to {@code minecraft} (so bare {@code "foo"} parses) and its {@code
     * isValidPath("")} is vacuously true (so {@code "foo:"} parses, with an empty path).
     * Neither is a channel a client would ever have. The two tests are complementary:
     * {@link #namespaceOf} plus the single-colon test fix the shape, {@code tryParse} fixes
     * the charset.
     */
    public static boolean isWellFormedChannelId(String channelId) {
        return channelId != null
                && namespaceOf(channelId) != null
                && channelId.indexOf(':') == channelId.lastIndexOf(':')
                && Identifier.tryParse(channelId) != null;
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
