package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.GuardConfig;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * The server key captured by {@code ConnectScreenMixin} at the moment a multiplayer
     * join begins -- before any {@code Connection} exists. See {@link #rememberServerKey}
     * for why this cannot instead be derived later from {@link Minecraft#getCurrentServer()}.
     * Consumed exactly once per connection by {@link #beginConnection}, which resets it to
     * {@code null} in the same step so a singleplayer session started right after a
     * multiplayer one can never inherit that server's grants.
     */
    private static final AtomicReference<String> REMEMBERED_SERVER_KEY = new AtomicReference<>();

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
     *
     * <p>{@code serverKey} is the key this connection's policy was actually built against
     * -- {@code "singleplayer"}, or the lowercased server address remembered by {@link
     * #rememberServerKey} at connect time. Carried on the snapshot, rather than left for a
     * caller to re-derive later, because the only other source -- {@link
     * Minecraft#getCurrentServer()} -- returns {@code null} for the entire configuration
     * phase (see {@link #rememberServerKey}'s Javadoc); a later task writing per-server
     * grants needs the key this connection is actually judged against, not a best-effort
     * re-guess. {@code null} only for {@link #globalsOnlySnapshot()}, which is a fallback
     * that by construction never learns which server it is talking to.
     */
    public record Snapshot(boolean active, boolean filterInbound, ExposurePolicy policy, String serverKey) {
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
     * Called on the client thread, before any {@code Connection} exists for the join in
     * progress -- from {@code ConnectScreenMixin}, at the {@code HEAD} of {@code
     * ConnectScreen#startConnecting}, which runs on the client thread (it calls {@code
     * minecraft.setScreen}) and strictly before the socket opens on the separate "Server
     * Connector #N" thread.
     *
     * <p><b>Why this can't wait until later, e.g. a re-read of {@code
     * Minecraft#getCurrentServer()} from inside the connection lifecycle:</b> in 1.21.11
     * that method is {@code Optionull.map(this.getConnection(), ClientPacketListener::
     * getServerData)}, and {@code getConnection()} returns the *play* listener -- which
     * does not exist for the entire configuration phase, exactly when every Fabric API
     * client-to-server payload is sent (see {@code NOTES.md}). Deriving the server key any
     * later than this method silently yields {@code "singleplayer"} for every real server
     * during that whole phase, which would mean per-server grants never apply to the
     * traffic this feature exists to filter. Capturing the key here, before the {@code
     * Connection} object is even constructed, is what avoids that hole.
     *
     * <p>Stores into {@link #REMEMBERED_SERVER_KEY} for {@link #beginConnection} to consume
     * exactly once. Fails closed on the thread contract: if this is somehow called off the
     * client thread, the remembered key is discarded (not left stale, not trusted) and the
     * upcoming connection falls back to {@code "singleplayer"} in {@link #beginConnection}
     * -- stricter than the real per-server policy could be, never more permissive.
     *
     * @param ip the {@code ServerData.ip} of the server being connected to; {@code null} is
     *           tolerated (treated the same as never having called this).
     */
    public static void rememberServerKey(String ip) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || !client.isSameThread()) {
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] rememberServerKey called off the client thread; discarding it "
                            + "so the upcoming connection falls back to \"singleplayer\" instead of "
                            + "risking a stale or wrong server key");
            REMEMBERED_SERVER_KEY.set(null);
            return;
        }
        REMEMBERED_SERVER_KEY.set(ip == null ? null : ip.toLowerCase(Locale.ROOT));
    }

    /**
     * Entry point for the connection lifecycle: called once per new connection. No longer
     * required to run on the client thread -- unlike {@link #rememberServerKey}, this
     * method touches no {@code Minecraft} state at all, only {@link #REMEMBERED_SERVER_KEY}
     * (a plain {@code AtomicReference} this class owns) and the {@link ChannelLedger}, so it
     * is safe to call from wherever the connection's constructor happens to run.
     *
     * <p>Consumes the remembered key <em>exactly once</em> -- reads it and resets it to
     * {@code null} in the same atomic step via {@link AtomicReference#getAndSet} -- and
     * defaults to {@code "singleplayer"} when none is set. One-shot consumption is
     * deliberate: it is what stops a singleplayer session that follows a multiplayer one
     * from inheriting that server's grants, without needing a second mixin on disconnect to
     * clear it.
     *
     * <p>Resets the ledger for the new session -- the ledger is deliberately global and
     * outlives a disconnect (see {@link ChannelLedger}'s Javadoc), so it must be cleared
     * explicitly at the *start* of the next connection rather than at the end of the last
     * one -- and pushes a fresh per-connection snapshot onto {@code connection}.
     *
     * <p>Safe to never call: if this is skipped, or a packet is sent before it runs,
     * {@code ConnectionMixin} falls back to {@link #globalsOnlySnapshot()} lazily on first
     * use, which cannot leak another connection's per-server grant because it never
     * consults per-server grants at all.
     *
     * @param connection the {@code Connection} instance for the new session. Typed as
     *                    {@code Object} would also work via {@code ConnectionInit}, but
     *                    taking the real type lets the compiler catch a wrong call site.
     */
    public static void beginConnection(Connection connection) {
        String serverKey = REMEMBERED_SERVER_KEY.getAndSet(null);
        if (serverKey == null) {
            serverKey = "singleplayer";
        }
        LEDGER.reset();
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
                config.exposure.policyFor(serverKey),
                serverKey);
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
     *
     * <p>{@code serverKey} is {@code null} on the returned snapshot: by definition this
     * fallback runs precisely when no real key has been established for the connection yet
     * (either {@link #beginConnection} has not run, or it consumed no remembered key), so
     * there is nothing genuine to report. A caller that writes per-server state (e.g. a
     * future {@code /cmdguard expose}) must treat a {@code null} serverKey as "not yet
     * known" and must not use it as a real key.
     */
    public static Snapshot globalsOnlySnapshot() {
        GuardConfig config = GuardConfig.get();
        ExposurePolicy policy = new ExposurePolicy(
                config.exposure.exposedNamespaces,
                config.exposure.exposedChannels,
                config.exposure.withheldChannels);
        return new Snapshot(config.enabled && config.exposure.enabled, config.exposure.filterInbound, policy, null);
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
