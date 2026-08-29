package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.GuardConfig;

import java.util.Locale;
import java.util.Set;

/**
 * The facade the mixins call. Everything here fails closed: if a decision cannot be made,
 * the payload is withheld and the reason is logged, because a privacy layer that fails
 * open without saying so is worse than none.
 */
public final class ExposureGuard {

    /** Every set empty: nothing but {@code ExposurePolicy.NEVER_WITHHELD} gets through. */
    private static final ExposurePolicy DENY_ALL =
            new ExposurePolicy(Set.of(), Set.of(), Set.of());

    private static final ChannelLedger LEDGER = new ChannelLedger();

    /** Snapshotted once per connection so a mid-session config edit cannot change it. */
    private static volatile ExposurePolicy policy;

    private ExposureGuard() {
    }

    public static ChannelLedger ledger() {
        return LEDGER;
    }

    /** Called when a new connection opens; the next payload rebuilds the snapshot. */
    public static void resetForNewConnection() {
        policy = null;
        LEDGER.reset();
    }

    private static ExposurePolicy policy() {
        ExposurePolicy current = policy;
        if (current == null) {
            current = GuardConfig.get().exposure.policyFor(currentServerKey());
            policy = current;
        }
        return current;
    }

    public static String currentServerKey() {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server == null || server.ip == null) {
            return "singleplayer";
        }
        return server.ip.toLowerCase(Locale.ROOT);
    }

    private static boolean active() {
        GuardConfig config = GuardConfig.get();
        return config.enabled && config.exposure.enabled;
    }

    /** True when this packet must not leave the client at all. */
    public static boolean shouldDrop(Packet<?> packet) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !active()) {
            return false;
        }
        try {
            String channel = channelOf(custom);
            boolean exposed = policy().isExposed(channel);
            LEDGER.record(channel, exposed);
            return !exposed;
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] exposure check failed, withholding", e);
            return true;
        }
    }

    /**
     * Returns the packet with withheld identifiers stripped, or the original unchanged
     * when nothing needed stripping.
     *
     * <p>By the time this runs, {@link #shouldDrop} has already decided the channel itself
     * may be disclosed -- so a rewrite failure must not fall back to the original packet:
     * that would leak identifiers <em>inside</em> a channel the player agreed to expose,
     * which is exactly the leak this feature exists to stop. Instead, on a
     * {@code RuntimeException} this retries the rewrite under {@link #DENY_ALL}, which
     * still lets {@link ExposurePolicy#NEVER_WITHHELD} channels through untouched. If that
     * retry also throws, the exception propagates and the send fails outright rather than
     * going out unfiltered.
     *
     * <p>In practice this catch is expected to be unreachable for a valid payload:
     * {@link PayloadRewriter} only calls {@code Identifier.parse} on strings that
     * round-tripped from {@code Identifier.toString()} of identifiers the game already
     * accepted, and every payload record it constructs is a plain record with no compact
     * constructor -- verified against the Fabric API 0.141.6 / fabric-networking-api-v1
     * 5.1.6, fabric-data-attachment-api-v1 1.8.48 and fabric-recipe-api-v1 8.2.4 sources
     * pinned in gradle.properties, none of which validate their arguments. The catch stays
     * because "cannot throw today" is not the same guarantee as "cannot throw ever" -- a
     * future dependency bump could add validation, and fail-closed must hold regardless.
     */
    public static Packet<?> rewriteOrSame(Packet<?> packet) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !active()) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        try {
            return rewritten(custom, payload, policy());
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] payload rewrite failed, retrying under deny-all policy", e);
            try {
                return rewritten(custom, payload, DENY_ALL);
            } catch (RuntimeException retryFailure) {
                CmdGuardClient.LOGGER.error(
                        "[cmdguard] payload rewrite failed under deny-all, withholding by breaking the send",
                        retryFailure);
                throw retryFailure;
            }
        }
    }

    private static Packet<?> rewritten(ServerboundCustomPayloadPacket custom,
                                       CustomPacketPayload payload,
                                       ExposurePolicy policy) {
        if (!PayloadRewriter.isRewritable(payload)) {
            return custom;
        }
        CustomPacketPayload filtered = PayloadRewriter.rewrite(payload, policy);
        return filtered == payload ? custom : new ServerboundCustomPayloadPacket(filtered);
    }

    /** False when an inbound payload on this channel must not reach the mod that owns it. */
    public static boolean allowInbound(Identifier channel) {
        GuardConfig config = GuardConfig.get();
        if (!active() || !config.exposure.filterInbound) {
            return true;
        }
        try {
            String id = channel.toString();
            boolean exposed = policy().isExposed(id);
            if (!exposed) {
                LEDGER.record(id, false);
            }
            return exposed;
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] inbound check failed, withholding", e);
            return false;
        }
    }

    private static String channelOf(ServerboundCustomPayloadPacket packet) {
        return packet.payload().type().id().toString();
    }
}
