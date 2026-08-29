package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.GuardConfig;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * One-shot flag set by a mixin on {@code ClientCommonPacketListenerImpl#handleTransfer}
     * and consumed by the very next {@link #beginConnection}. See that method's Javadoc for
     * why a server transfer needs this and why leaving it dangling on a cancelled or failed
     * transfer is acceptable -- unlike a dangling server key (the design this replaced), a
     * dangling strictness flag can only ever cost the player a per-server grant, never leak
     * one, so there is no failure mode here that needs a second cleanup mixin.
     */
    private static final AtomicBoolean NEXT_CONNECTION_IS_TRANSFER = new AtomicBoolean(false);

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
     * -- {@code "singleplayer"}, or the lowercased server address {@code
     * ClientCommonPacketListenerImplMixin} read from the connection's own {@code
     * CommonListenerCookie}. Carried on the snapshot, rather than left for a caller to
     * re-derive later, because a later task writing per-server grants needs the key this
     * connection is actually judged against. {@code null} only for {@link
     * #globalsOnlySnapshot()}, which is a fallback that by construction never learns which
     * server it is talking to.
     */
    public record Snapshot(boolean active, boolean filterInbound, ExposurePolicy policy, String serverKey) {
    }

    /**
     * Implemented by {@code ConnectionMixin}. The connection-lifecycle hook calls {@code
     * cmdguard$initExposure(Snapshot)} once per {@code Connection}, as soon as the server
     * key for that connection is known and before any packet can be sent on it. See {@link
     * #beginConnection}.
     */
    public interface ConnectionInit {

        /**
         * Installs {@code snapshot} as this connection's frozen decision surface, but only
         * if none is installed yet. Returns whether this call actually installed it --
         * {@code false} means a snapshot was already present, and this call changed
         * nothing.
         *
         * <p>The idempotence matters here specifically because {@code
         * ClientCommonPacketListenerImpl}'s shared base constructor runs more than once for
         * the very same {@code Connection}: once building the configuration-phase listener,
         * again building the play-phase listener, and a third time on a mid-game
         * reconfigure. Without this check, the second call would silently replace a real
         * per-server snapshot with whatever the caller passes on that later call -- and,
         * before this fix, that second call had no remembered key left to consume and so
         * fell back to {@code "singleplayer"}, meaning a local-world grant would apply for
         * the rest of a real server connection. Rejecting a second install closes that.
         */
        boolean cmdguard$initExposure(Snapshot snapshot);

        /** The snapshot frozen for this connection, never null -- globals-only if uninitialised. */
        Snapshot cmdguard$snapshot();
    }

    /**
     * Set by a mixin on {@code ClientCommonPacketListenerImpl#handleTransfer}, the moment a
     * server-initiated transfer begins. Consumed exactly once by the next {@link
     * #beginConnection} call, which then installs {@link #globalsOnlySnapshot()} instead of
     * a per-server one for that connection.
     *
     * <p><b>Why a transfer needs this at all:</b> {@code handleTransfer} calls {@code
     * ConnectScreen.startConnecting(..., this.serverData, ...)} -- passing the
     * <em>original</em> server's {@code ServerData} into the connection being opened to the
     * <em>destination</em> server. That {@code ServerData} flows straight into the new
     * connection's {@code CommonListenerCookie}, so deriving the server key from the cookie
     * (as {@link #beginConnection} now does) would silently hand the destination server the
     * origin server's per-server grants -- exactly the permissive direction this whole
     * design refuses. Forcing the transferred-to connection onto the globals-only snapshot
     * instead is stricter than guessing right would be, and can never be wrong in the
     * dangerous direction.
     *
     * <p><b>Why this dangling flag is safe when the by-key design's dangling state was
     * not:</b> a stray, un-consumed {@code true} here can only make some later, unrelated
     * connection run under the globals-only snapshot instead of its real per-server one --
     * strictly stricter, never a leak. A stray remembered <em>key</em> (the design this
     * replaced) could instead hand a connection a real server's grants it never earned,
     * which is the dangerous direction. That asymmetry -- cost the player a grant vs. leak
     * one -- is the entire justification for tolerating cross-call state here and nowhere
     * else in this class. Do not "clean this up" into carrying a key across calls again.
     */
    public static void markNextConnectionAsTransfer() {
        NEXT_CONNECTION_IS_TRANSFER.set(true);
    }

    /**
     * Entry point for the connection lifecycle: called once per new connection -- and,
     * because {@code ClientCommonPacketListenerImpl}'s shared constructor runs more than
     * once against the same {@code Connection} (see {@link ConnectionInit#cmdguard$initExposure}),
     * potentially called two or three times for what is really one connection. Touches no
     * {@code Minecraft} state at all -- only {@link #NEXT_CONNECTION_IS_TRANSFER} (a plain
     * {@code AtomicBoolean} this class owns) and the {@link ChannelLedger} -- so it is safe
     * to call from wherever the caller's constructor happens to run, client thread or netty
     * event loop.
     *
     * <p>Idempotent per connection: only the call that actually installs a snapshot (see
     * {@link ConnectionInit#cmdguard$initExposure}) resets the ledger. A second or third
     * call for the same connection does nothing -- deliberately not resetting the ledger
     * again, since the ledger is what surfaces everything recorded during the configuration
     * phase (see {@link ChannelLedger}'s "outlives a disconnect" Javadoc), and wiping it on
     * the play-phase construction would erase exactly the traffic this feature exists to
     * surface.
     *
     * <p>Consumes {@link #NEXT_CONNECTION_IS_TRANSFER} exactly once per call (not per
     * connection) via {@link AtomicBoolean#getAndSet} -- harmless, because only the first
     * call for a given connection can ever actually install a snapshot; a later call for
     * the same connection reads the flag as already consumed and is a no-op regardless.
     *
     * <p>Safe to never call: if this is skipped, or a packet is sent before it runs, {@code
     * ConnectionMixin} falls back to {@link #globalsOnlySnapshot()} lazily on first use,
     * which cannot leak another connection's per-server grant because it never consults
     * per-server grants at all.
     *
     * @param connection the {@code Connection} instance for the new session. Typed as
     *                    {@code Object} would also work via {@code ConnectionInit}, but
     *                    taking the real type lets the compiler catch a wrong call site.
     * @param serverKey   {@code "singleplayer"}, or the lowercased server address the caller
     *                    read from this connection's {@code CommonListenerCookie}.
     */
    public static void beginConnection(Connection connection, String serverKey) {
        if (!(connection instanceof ConnectionInit init)) {
            return;
        }
        boolean isTransfer = NEXT_CONNECTION_IS_TRANSFER.getAndSet(false);
        Snapshot snapshot = isTransfer ? globalsOnlySnapshot() : snapshotFor(serverKey);
        if (init.cmdguard$initExposure(snapshot)) {
            LEDGER.reset();
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
     * The safety-net snapshot used when no per-connection snapshot has been installed yet,
     * and also what a transfer-flagged connection deliberately gets instead of a per-server
     * snapshot (see {@link #markNextConnectionAsTransfer}). Deliberately does not consult
     * per-server grants -- {@code exposedNamespaces} only, built directly from the global
     * config fields rather than through {@code ExposureSettings#policyFor}, so there is no
     * server-key lookup here at all for a stale read to go wrong on. This is stricter than
     * the real per-server policy could be, never more permissive, so it cannot leak a grant
     * that belongs to a different server's connection.
     *
     * <p>Deliberately <em>not</em> a deny-all policy either: a deny-all policy withholds
     * {@code minecraft:register}, which stops the server's registry sync and breaks joining
     * a modded server outright. Globals-only is the correct middle ground: stricter than
     * the configured policy might otherwise be, but still lets a normal join complete.
     *
     * <p>{@code serverKey} is {@code null} on the returned snapshot: by definition this
     * fallback runs precisely when no real key has been established for the connection yet,
     * or has been deliberately withheld (the transfer case), so there is nothing genuine to
     * report. A caller that writes per-server state (e.g. a future {@code /cmdguard
     * expose}) must treat a {@code null} serverKey as "not yet known" and must not use it as
     * a real key.
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
