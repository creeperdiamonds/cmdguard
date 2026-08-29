package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
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
 *
 * <p><b>Per-connection state, not a static field.</b> An earlier version of this class kept
 * a single {@code static volatile ExposurePolicy policy}, lazily computed on first use and
 * cleared by a {@code resetForNewConnection()} call. That is a check-then-act race with no
 * mutual exclusion between the read of {@code policy}, the compute, and the write: two
 * threads (the client thread and the netty event loop both provably call into
 * {@code sendPacket}) can both observe {@code null} and both compute, and -- once something
 * calls reset between connections -- a descheduled thread can finish computing server A's
 * snapshot *after* the reset for server B has already run, and install server A's grants
 * onto server B's connection. That is a real cross-connection leak, not a theoretical one.
 * Storing the snapshot on the {@code Connection} instance itself (see {@link Snapshot},
 * {@link ConnectionInit}, and {@code ConnectionMixin}'s {@code @Unique} field) makes that
 * structurally impossible instead of merely unlikely: a new connection is a new object, so
 * there is no shared cell for a stale write to land in.
 */
public final class ExposureGuard {

    /** Every set empty: nothing but {@code ExposurePolicy.NEVER_WITHHELD} gets through. */
    private static final ExposurePolicy DENY_ALL = new ExposurePolicy(Set.of(), Set.of(), Set.of());

    private static final ChannelLedger LEDGER = new ChannelLedger();

    private ExposureGuard() {
    }

    public static ChannelLedger ledger() {
        return LEDGER;
    }

    /**
     * The whole outbound decision surface for one connection, frozen together into one
     * immutable object. Freezing {@code active} and {@code filterInbound} alongside the
     * policy -- rather than re-reading {@code GuardConfig.get().enabled} on every packet --
     * is what stops a mid-session {@code /cmdguard off} (or a config-screen edit) from
     * filtering, say, the configuration phase of a connection but not its play phase: once
     * a connection has its snapshot, that connection sees one consistent answer for its
     * whole lifetime, and a live edit takes effect starting with the next connection.
     */
    public record Snapshot(boolean active, boolean filterInbound, ExposurePolicy policy) {
    }

    /**
     * Implemented by {@code ConnectionMixin}. The connection-lifecycle hook calls
     * {@code cmdguard$initExposure(String)} on the client thread, once per {@code
     * Connection}, as soon as the server key for that connection is known and before any
     * packet can be sent on it. See {@link #beginConnection}.
     */
    public interface ConnectionInit {
        void cmdguard$initExposure(String serverKey);

        /** The snapshot frozen for this connection, never null -- globals-only if uninitialised. */
        Snapshot cmdguard$snapshot();
    }

    /**
     * Entry point for the connection lifecycle (wired by a later task): called once per new
     * connection, on the client thread. Resets the ledger for the new session -- the ledger
     * is deliberately global and outlives a disconnect (see {@link ChannelLedger}'s
     * Javadoc), so it must be cleared explicitly at the *start* of the next connection
     * rather than at the end of the last one -- and pushes a fresh per-connection snapshot
     * onto {@code connection}.
     *
     * <p>Safe to never call: if this is skipped, or a packet is sent before it runs,
     * {@code ConnectionMixin} falls back to {@link #globalsOnlySnapshot()} lazily on first
     * use, which cannot leak another connection's per-server grant because it never
     * consults per-server grants at all.
     *
     * @param connection the {@code Connection} instance for the new session. Typed as
     *                    {@code Object} would also work via {@code ConnectionInit}, but
     *                    taking the real type lets the compiler catch a wrong call site.
     * @param serverKey   must be computed on the client thread via {@link #currentServerKey()}
     *                    -- see that method's Javadoc for why.
     */
    public static void beginConnection(Connection connection, String serverKey) {
        Minecraft client = Minecraft.getInstance();
        LEDGER.reset();
        if (client == null || !client.isSameThread()) {
            // Enforce the documented contract rather than trust the caller: reading Minecraft
            // state (which serverKey was presumably computed from) off the client thread is
            // exactly the stale-read defect this class's Javadoc warns about. Deliberately do
            // NOT call cmdguard$initExposure here -- leaving the connection's snapshot field
            // unset lets ConnectionMixin's own lazy fallback install globalsOnlySnapshot() on
            // first use, which is strictly stricter than any per-server policy and safe from
            // any thread.
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] beginConnection called off the client thread; "
                            + "leaving this connection on the globals-only fallback snapshot");
            return;
        }
        if (connection instanceof ConnectionInit init) {
            init.cmdguard$initExposure(serverKey);
        }
    }

    /** Builds the full per-connection snapshot, including this server's own grants. */
    public static Snapshot snapshotFor(String serverKey) {
        GuardConfig config = GuardConfig.get();
        return new Snapshot(
                config.enabled && config.exposure.enabled,
                config.exposure.filterInbound,
                config.exposure.policyFor(serverKey));
    }

    /**
     * The safety-net snapshot used when no per-connection snapshot has been installed yet.
     * Deliberately does not consult per-server grants -- {@code exposedNamespaces} only,
     * built directly from the global config fields rather than through
     * {@code ExposureSettings#policyFor}, so there is no server-key lookup here at all for
     * a stale read to go wrong on. This is stricter than the real per-server policy could
     * be, never more permissive, so it cannot leak a grant that belongs to a different
     * server's connection.
     *
     * <p>Deliberately <em>not</em> a deny-all policy either: a deny-all policy withholds
     * {@code minecraft:register}, which stops the server's registry sync and breaks joining
     * a modded server outright. Globals-only is the correct middle ground: stricter than
     * the configured policy might otherwise be, but still lets a normal join complete.
     */
    public static Snapshot globalsOnlySnapshot() {
        GuardConfig config = GuardConfig.get();
        ExposurePolicy policy = new ExposurePolicy(
                config.exposure.exposedNamespaces,
                config.exposure.exposedChannels,
                config.exposure.withheldChannels);
        return new Snapshot(config.enabled && config.exposure.enabled, config.exposure.filterInbound, policy);
    }

    /**
     * Must be called from the client thread. It reads {@code Minecraft.getInstance()} and
     * the current {@code ServerData}, both of which the game only guarantees to be
     * consistent when read from the thread that owns them; reading them from the netty
     * event loop with no happens-before edge back to the client thread can observe a stale
     * value (e.g. the previous server, after the player has already moved on), and that
     * stale read is not self-correcting once frozen into a connection's snapshot.
     */
    public static String currentServerKey() {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server == null || server.ip == null) {
            return "singleplayer";
        }
        return server.ip.toLowerCase(Locale.ROOT);
    }

    /** True when this packet must not leave the client at all. */
    public static boolean shouldDrop(Packet<?> packet, Snapshot snapshot) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !snapshot.active()) {
            return false;
        }
        try {
            String channel = channelOf(custom);
            boolean exposed = snapshot.policy().isExposed(channel);
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
    public static Packet<?> rewriteOrSame(Packet<?> packet, Snapshot snapshot) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !snapshot.active()) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        try {
            return rewritten(custom, payload, snapshot.policy());
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
    public static boolean allowInbound(Identifier channel, Snapshot snapshot) {
        if (!snapshot.active() || !snapshot.filterInbound()) {
            return true;
        }
        try {
            String id = channel.toString();
            boolean exposed = snapshot.policy().isExposed(id);
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
