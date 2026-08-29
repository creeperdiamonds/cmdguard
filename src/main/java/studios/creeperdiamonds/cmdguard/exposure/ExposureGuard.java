package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.network.protocol.login.custom.DiscardedQueryPayload;
import net.minecraft.resources.Identifier;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.GuardConfig;

import java.util.List;
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

    /**
     * The reserved server key for a connection with no {@code ServerData} -- a local world's
     * client-to-integrated-server connection.
     *
     * <p>The key is deliberately kept even though {@link #snapshotFor} switches filtering off
     * for it: the spec's per-world grant separation is built on this key existing and staying
     * distinct from every real server's. Only {@code active} changes.
     */
    public static final String SINGLEPLAYER_KEY = "singleplayer";

    /** Every set empty: nothing but {@code ExposurePolicy.NEVER_WITHHELD} gets through. */
    private static final ExposurePolicy DENY_ALL = new ExposurePolicy(Set.of(), Set.of(), Set.of());

    private static final ChannelLedger LEDGER = new ChannelLedger();

    /**
     * The id used for a substituted login-query payload when the decision could not be made
     * at all -- the real channel could not be read, or something else in {@link
     * #forceVanillaLoginAnswer} threw. Unreachable with any payload vanilla or Fabric API
     * produces (all of them are records whose {@code id()} is a field read), but a
     * third-party {@code CustomQueryPayload} can return a null id, so it exists to let that
     * whole path still fail closed rather than throw. It never reaches the wire: see {@link
     * #forceVanillaLoginAnswer}'s "not fabrication" paragraph.
     */
    private static final Identifier WITHHELD_QUERY_MARKER =
            Identifier.fromNamespaceAndPath("cmdguard", "withheld_login_query");

    /**
     * One-shot flag set by a mixin on {@code ClientCommonPacketListenerImpl#handleTransfer}
     * and consumed by the {@link #beginConnection} call whose install actually succeeds --
     * not by whichever call happens to run next. See that method's Javadoc and {@link
     * #markNextConnectionAsTransfer}'s for why a server transfer needs this, why leaving it
     * dangling on a cancelled or failed transfer is acceptable, and why "consumed by the
     * next call" (an earlier version of this comment, and this flag's earlier behaviour)
     * was wrong: a stray listener construction on the *origin* connection between the flag
     * being set and the destination connection being constructed -- reachable under
     * server-chosen packet ordering, e.g. a reconfigure batched just ahead of the transfer --
     * would otherwise consume the flag on a call that never installs anything, silently
     * discarding it before the destination connection ever gets a chance to read it.
     */
    private static final AtomicBoolean NEXT_CONNECTION_IS_TRANSFER = new AtomicBoolean(false);

    private ExposureGuard() {
    }

    public static ChannelLedger ledger() {
        return LEDGER;
    }

    /**
     * The active connection's snapshot, or null when not connected.
     *
     * <p>Client-thread only. This walks the play listener to reach the connection, so it is
     * for commands and screens -- never for the filtering path, which must use the snapshot
     * held on its own {@link ConnectionInit}.
     */
    public static Snapshot currentSnapshot() {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener listener = client == null ? null : client.getConnection();
        if (listener == null) {
            return null;
        }
        return ((ConnectionInit) (Object) listener.getConnection()).cmdguard$snapshot();
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
     *
     * <p>{@code filterLogin} is frozen here alongside the rest, but note that in the login
     * phase it is only ever read off a {@link #globalsOnlySnapshot()} -- {@link
     * #beginConnection} runs from {@code ClientCommonPacketListenerImpl}'s constructor, which
     * is reached at {@code handleLoginFinished}, i.e. strictly after every login query has
     * been answered. See {@link #forceVanillaLoginAnswer}.
     */
    public record Snapshot(boolean active,
                           boolean filterInbound,
                           boolean filterLogin,
                           ExposurePolicy policy,
                           String serverKey) {
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
     *
     * <p><b>That asymmetry justifies dangling, but not mis-consumption, and an earlier
     * version of this fix conflated the two.</b> {@link #beginConnection} used to consume
     * this flag unconditionally on every call via {@code getAndSet(false)}, on the
     * reasoning that only the first call for a given connection can ever install a
     * snapshot. That reasoning covers the same connection consuming its own flag twice; it
     * does not cover a *different* connection's call consuming a flag meant for this one.
     * Concretely: {@code handleTransfer} sets this flag, then calls {@code
     * ConnectScreen.startConnecting} on the *origin* connection, which is still alive at
     * that point. If the server batches a reconfigure (or anything else that triggers
     * another {@code ClientCommonPacketListenerImpl} construction) on that origin
     * connection before the destination connection is constructed, that construction's
     * {@code beginConnection} call would consume (and discard) this flag even though its
     * own install is rejected by {@link ConnectionInit#cmdguard$initExposure} (the origin
     * connection already has a snapshot) -- burning the flag on a no-op and leaving the
     * destination connection to key itself to the origin server's grants after all,
     * exactly the leak this flag exists to prevent. {@link #beginConnection} now consumes
     * this flag only inside the branch where its own install actually succeeds, which the
     * origin connection's spurious call never reaches.
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
     * {@code AtomicBoolean} this class owns), the {@link ChannelLedger}, and the logger --
     * so it is safe to call from wherever the caller's constructor happens to run, client
     * thread or netty event loop.
     *
     * <p>The winning call also logs two lines: the outgoing connection's tally, emitted
     * before the ledger is reset (see {@link #logPreviousConnectionSummary()}), and a line
     * naming the new connection's key and whether filtering is on at all. That second line
     * is deliberate observability: without it, a filter that is switched off, a filter that
     * silently failed to install, and a genuinely quiet session all look identical in the
     * log -- which is precisely how the inbound filter's mixin-ordering defect survived.
     *
     * <p>Idempotent per connection, and this is now a hard guarantee rather than an
     * ordering assumption: {@link ConnectionInit#cmdguard$initExposure} installs via a
     * genuine compare-and-set on a field written only by that method (see {@code
     * ConnectionMixin}'s {@code cmdguard$snapshot} field Javadoc), so across any number of
     * calls and any thread interleaving, exactly one call for a given {@code Connection}
     * ever has its install succeed. Only that call resets the ledger. A call whose install
     * is rejected does nothing else -- deliberately not resetting the ledger again, since
     * the ledger is what surfaces everything recorded during the configuration phase (see
     * {@link ChannelLedger}'s "outlives a disconnect" Javadoc), and wiping it on the
     * play-phase construction would erase exactly the traffic this feature exists to
     * surface.
     *
     * <p>Reads {@link #NEXT_CONNECTION_IS_TRANSFER} first (a peek, not a consume) to decide
     * which snapshot to attempt installing, then consumes it via {@link
     * AtomicBoolean#compareAndSet(boolean, boolean) compareAndSet(true, false)} <em>only
     * inside the branch where this call's own install succeeds</em>. This is deliberate,
     * and different from an earlier version of this method that consumed the flag
     * unconditionally on every call: see {@link #markNextConnectionAsTransfer}'s Javadoc
     * for the concrete scenario (a stray construction on the origin connection of a
     * transfer) that made unconditional consumption a real leak. Gating consumption on this
     * call's own success means a call whose install is rejected -- which is exactly the
     * failure mode in that scenario -- never touches the flag, leaving it for the call that
     * actually needs it.
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
        boolean isTransfer = NEXT_CONNECTION_IS_TRANSFER.get();
        Snapshot snapshot = isTransfer ? globalsOnlySnapshot() : snapshotFor(serverKey);
        if (init.cmdguard$initExposure(snapshot)) {
            logPreviousConnectionSummary();
            LEDGER.reset();
            CmdGuardClient.LOGGER.info(
                    "[cmdguard] exposure filter armed for {}: filtering={}, inbound filtering={},"
                            + " login filtering={} (login queries were already handled under the"
                            + " globals-only policy before this line)",
                    describeKey(snapshot.serverKey()), snapshot.active(), snapshot.filterInbound(),
                    snapshot.filterLogin());
            if (isTransfer) {
                NEXT_CONNECTION_IS_TRANSFER.compareAndSet(true, false);
            }
        }
    }

    /**
     * The previous connection's tally, logged <em>before</em> {@link ChannelLedger#reset()}
     * wipes it.
     *
     * <p>{@code /cmdguard exposure} needs an active connection to run, so it is unreachable
     * from the disconnect screen -- and the ledger is reset the moment the next connection
     * installs its snapshot. Without this line, a player kicked for withholding a required
     * mod has no way at all to read what was withheld: the chat is gone, the command is
     * unreachable, and reconnecting destroys the record. This puts it in {@code latest.log},
     * which outlives all three.
     */
    private static void logPreviousConnectionSummary() {
        ChannelLedger.Counts counts = LEDGER.counts();
        if (counts.isEmpty()) {
            return;
        }
        CmdGuardClient.LOGGER.info(
                "[cmdguard] previous connection: exposed={} withheld={} ({} payloads withheld in total)",
                counts.exposed(), counts.withheld(), counts.withheldPayloads());
    }

    /**
     * Builds the full per-connection snapshot, including this server's own grants.
     *
     * <p><b>Singleplayer is exempt.</b> A local world's connection runs between the client
     * and the integrated server in the same JVM: there is no remote party, so there is no
     * disclosure to control. Filtering it bought nothing and cost real function --
     * {@code minecraft:register}, {@code c:register}, accepted-attachments, recipe
     * serializers and custom ingredients all had third-party identifiers stripped while
     * talking to the player's own process, degrading other mods' networking, attachment sync
     * and custom recipes for no privacy gain whatsoever. So {@code active} is false for
     * {@link #SINGLEPLAYER_KEY}.
     *
     * <p>The key itself stays: per-world grants are keyed on it and must stay separate from
     * every real server's, and "Open to LAN" does not change the key (a LAN <em>host</em>
     * is still this same in-process connection; a LAN <em>join</em> carries a real
     * {@code ServerData} and gets a real key, so it is filtered like any other server).
     */
    public static Snapshot snapshotFor(String serverKey) {
        GuardConfig config = GuardConfig.get();
        return new Snapshot(
                config.exposureActive() && !SINGLEPLAYER_KEY.equals(serverKey),
                config.exposure.filterInbound,
                config.exposure.filterLogin,
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
        return new Snapshot(config.exposureActive(),
                config.exposure.filterInbound,
                config.exposure.filterLogin,
                policy,
                null);
    }

    /** True when this packet must not leave the client at all. */
    public static boolean shouldDrop(Packet<?> packet, Snapshot snapshot) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !snapshot.active()) {
            return false;
        }
        try {
            String channel = channelOf(custom);
            boolean exposed = snapshot.policy().isExposed(channel);
            if (LEDGER.record(channel, exposed)) {
                logFirstWithhold("outbound", channel, snapshot);
            }
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
        return allowInbound(channel, snapshot, "inbound");
    }

    /**
     * {@link #allowInbound(Identifier, Snapshot)}, with the log line's {@code direction} word
     * overridable so a payload found inside a bundle logs distinguishably from a bare one.
     *
     * <p>Without this, {@link #filterBundle}'s path and the bare {@code channelRead0} path
     * produced the exact same log line, so a real session could never tell whether the
     * bundle filter had fired at all -- the same blind spot that let the bundle filter's
     * predecessor (the inbound mixin-ordering defect) ship unnoticed. {@code direction} is
     * otherwise opaque to this method: it only ever reaches {@link #logFirstWithhold}.
     */
    private static boolean allowInbound(Identifier channel, Snapshot snapshot, String direction) {
        if (!snapshot.active() || !snapshot.filterInbound()) {
            return true;
        }
        try {
            String id = channel.toString();
            boolean exposed = snapshot.policy().isExposed(id);
            if (!exposed && LEDGER.record(id, false)) {
                logFirstWithhold(direction, id, snapshot);
            }
            return exposed;
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] inbound check failed, withholding", e);
            return false;
        }
    }

    /**
     * Returns {@code bundle} with every withheld custom-payload sub-packet removed, or the
     * very same object when nothing needed removing.
     *
     * <p><b>Why this exists at all.</b> {@code ConnectionMixin}'s inbound {@code instanceof
     * ClientboundCustomPayloadPacket} test is not enough on its own, because in the play
     * protocol a custom payload does not have to arrive as its own pipeline message.
     * Verified 2026-08-29 against the decompiled 1.21.11 sources and the mapped merged jar
     * (see {@code NOTES.md}, "Inbound: packet bundles"): the play clientbound protocol is
     * the only protocol with a non-null {@code ProtocolInfo#bundlerInfo()}, so {@code
     * Connection#setupInboundProtocol} installs a {@code PacketBundlePacker} as {@code
     * "bundler"} directly after {@code "decoder"} and therefore ahead of {@code
     * "packet_handler"}; that handler swallows every packet between two {@code
     * ClientboundBundleDelimiterPacket}s and emits a single {@code ClientboundBundlePacket}
     * in their place. {@code channelRead0} then sees only the bundle. {@code
     * ClientPacketListener#handleBundlePacket} unpacks it on the client thread with a plain
     * {@code subPacket.handle(this)} per sub-packet -- which for a bundled {@code
     * ClientboundCustomPayloadPacket} lands straight in {@code
     * ClientCommonPacketListenerImpl#handleCustomPayload}, i.e. in Fabric API's addon
     * dispatch, without ever passing the inbound filter again. {@code
     * CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD} is registered in the play clientbound
     * protocol and {@code Packet#isTerminal()} is false for it, so the bundler will accept
     * one: a server that wants to probe a client can simply wrap its probe in a bundle.
     *
     * <p><b>Removal only, and only of custom payloads.</b> Bundles are how vanilla batches
     * entity spawn traffic, so dropping an arbitrary sub-packet would break the game and
     * dropping the whole bundle would take unrelated packets with it. Nothing is ever added
     * or altered here -- the walk-drop-preserve-order-defer-allocation logic itself lives in
     * {@link BundleFilter#retainAllowed}, unit-tested there with plain strings; this method
     * only supplies the drop predicate and rebuilds {@link ClientboundBundlePacket} from
     * whatever subset comes back, in the original order. That keeps this inside the same
     * no-fabrication rule as {@link IdentifierFilter}: withholding is silence.
     *
     * <p><b>An emptied bundle is emitted, not cancelled, and that is safe.</b> Read, not
     * assumed: a grep of the decompiled sources finds two callers of {@code subPackets()} in
     * the base game -- {@code ClientPacketListener#handleBundlePacket}, whose body is
     * {@code ensureRunningOnSameThread} followed by a bare for-each, and {@code
     * BundlerInfo.unbundlePacket}, which is the server's outbound path and is never reached
     * here. Fabric API 0.141.6 adds a third at runtime -- {@code BundlePacketMixin}, which
     * reads a bundle's sub-packets to flatten nested bundles at construction time -- and this
     * mod hard-depends on Fabric API, so it is never truly absent from the running game. The
     * conclusion is unaffected: that mixin's body is also a bare copy into a fresh {@code
     * ArrayList}, so it too runs zero times, and does nothing else, on an empty bundle. An
     * empty bundle therefore does nothing at all in any of the three; no code path asserts a
     * non-empty bundle. Emitting it rather than cancelling keeps the decision in one place
     * and leaves {@code Connection}'s {@code receivedPackets} counter honest.
     *
     * <p><b>Fail closed.</b> A sub-packet whose channel id cannot even be read is withheld
     * rather than kept (see {@link #allowBundledPayload}), and {@link #allowInbound} already
     * withholds on any exception of its own, so the loop itself cannot throw. If iterating
     * the bundle or rebuilding it throws anyway, the exception is logged and rethrown: it
     * propagates out of {@code channelRead0} into {@code Connection#exceptionCaught}, which
     * disconnects. That loses the connection, which is the point -- the alternative is
     * handing the listener a bundle this method could not filter. It is not a path vanilla
     * or Fabric API can reach: both build a bundle's sub-packet list as an {@code ArrayList}
     * ({@code BundlerInfo.createForPacket}'s bundler, and Fabric API 0.141.6's {@code
     * BundlePacketMixin}, which flattens nested bundles into one at {@code BundlePacket}'s
     * constructor).
     */
    public static Packet<?> filterBundle(ClientboundBundlePacket bundle, Snapshot snapshot) {
        if (!snapshot.active() || !snapshot.filterInbound()) {
            return bundle;
        }
        try {
            List<Packet<? super ClientGamePacketListener>> kept = BundleFilter.retainAllowed(
                    bundle.subPackets(),
                    sub -> sub instanceof ClientboundCustomPayloadPacket custom
                            && !allowBundledPayload(custom, snapshot));
            return kept == null ? bundle : new ClientboundBundlePacket(kept);
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] bundle filter failed; refusing to deliver an unfiltered bundle", e);
            throw e;
        }
    }

    /**
     * The login-phase choke point: returns {@code packet} with its payload replaced by a
     * {@code DiscardedQueryPayload} when the queried channel is withheld, or the very same
     * object when it is not.
     *
     * <p><b>Why a substitution and not a drop.</b> The rest of this class withholds by
     * dropping. That is not available here. Nothing in vanilla does per-transaction
     * accounting -- there is no map, set or counter keyed by transaction id on either side --
     * so an unanswered query is not refused, it simply stalls: a querying server is by
     * construction blocked on that transaction, so it sends nothing, and after 30 s of
     * receiving nothing the client's own {@code ReadTimeoutHandler(30)} (installed in
     * {@code Connection}) fires {@code disconnect.timeout}. A hang is a behaviour no vanilla
     * client exhibits, so it would disclose strictly more than the answer it was meant to
     * avoid. Substituting instead means: nothing is cancelled, no packet this mod constructs
     * ever goes on the wire, the transaction id is preserved, and the answer that is sent is
     * produced by unmodified vanilla code.
     *
     * <p><b>How the substitution works.</b> Vanilla's
     * {@code ClientHandshakePacketListenerImpl#handleCustomQuery} is unconditional -- it reads
     * no channel, has no recognised-channel set, and always sends
     * {@code new ServerboundCustomQueryAnswerPacket(transactionId, null)}. Fabric API's
     * {@code ClientHandshakePacketListenerImplMixin} cancels that send only when
     * {@code packet.payload() instanceof PacketByteBufLoginQueryRequestPayload} <em>and</em>
     * its addon finds a registered handler for the channel. A {@code DiscardedQueryPayload}
     * fails that {@code instanceof}, so the addon is never consulted and vanilla's send stands.
     * Verified 2026-08-29 with {@code javap} against the mapped 1.21.11 merged jar (both
     * constructors used here are public:
     * {@code ClientboundCustomQueryPacket(int, CustomQueryPayload)} and
     * {@code DiscardedQueryPayload(Identifier)}) and against the Fabric API 0.141.6 sources
     * pinned in {@code gradle.properties}.
     *
     * <p><b>Reading the channel id.</b> {@code CustomQueryPayload} declares
     * {@code Identifier id()}, and Fabric API's {@code ClientboundCustomQueryPacketMixin}
     * replaces the decoded payload of <em>every</em> login query, unconditionally, with a
     * {@code PacketByteBufLoginQueryRequestPayload(Identifier id, FriendlyByteBuf data)} --
     * which implements {@code CustomQueryPayload}. So the id is read through the vanilla
     * interface and this file needs no Fabric {@code impl} import to get it. Vanilla's own
     * {@code readUnknownPayload} produces a {@code DiscardedQueryPayload}, which carries the
     * id too, so the read is correct with or without Fabric's mixin.
     *
     * <p><b>A null answer is a refusal, not a lie.</b> The record component is declared
     * {@code @Nullable CustomQueryAnswerPayload payload} and is written with
     * {@code writeNullable}, so null is the protocol's own encoding of "there is no payload",
     * not a value invented to stand in for one. It carries zero identifiers --
     * {@code CustomQueryAnswerPayload} declares only {@code write(FriendlyByteBuf)}, no
     * {@code id()}, so the answer packet never names a channel at all. It is vanilla's
     * unconditional behaviour rather than a pose adopted for the occasion, and Fabric's own
     * API treats it as the sanctioned decline value: a registered handler whose future
     * completes with {@code null} emits a byte-identical packet. This is the same act as
     * omitting a channel from {@code minecraft:register} -- one phase earlier.
     *
     * <p><b>Per-server grants cannot apply here, and that is structural.</b> {@link
     * #beginConnection} runs from {@code ClientCommonPacketListenerImpl}'s constructor, which
     * is first reached at {@code handleLoginFinished} -- after the login phase is over. So no
     * per-connection snapshot exists while login queries are arriving, and {@code
     * ConnectionMixin#cmdguard$snapshot()} necessarily returns {@link #globalsOnlySnapshot()}.
     * The remedy for a login broken by this filter is therefore always a global command plus a
     * reconnect -- {@code /cmdguard expose global <namespace>}, or {@code /cmdguard expose
     * channel <id>} when the channel was withheld by name and the namespace form provably
     * could not help. {@link LoginQueryFilter#remedyCommand} picks between them; the
     * per-server form is never right here, and telling a user to run it would leave them
     * concluding the mod is broken.
     *
     * <p><b>The warning is not polish.</b> If a server's handshake genuinely needs a real
     * answer, withholding it breaks the join -- and it surfaces as the <em>server's</em>
     * disconnect screen, with nothing on it pointing at this mod. There is no chat to write
     * to and {@code /cmdguard exposure} is unreachable from a disconnect screen, so {@code
     * latest.log} is the only place the cause can appear. It is logged at WARN, once per
     * substitution rather than once per channel: vanilla sends no login queries at all, so the
     * volume is bounded by what the server asks and there is nothing to flood. The {@link
     * ChannelLedger} deliberately is <em>not</em> written here -- {@link #beginConnection}
     * resets it at {@code handleLoginFinished}, which is after every login query, so a login
     * entry would be wiped before any user could read it and would be counted against the
     * <em>previous</em> connection's tally on the way out.
     *
     * <p><b>Fail closed, and <em>total</em>.</b> The decision itself is {@link
     * LoginQueryFilter#withholds}, which withholds on a null id, a null policy and any
     * exception. But the steps around it can throw too, and this method must never let one
     * escape: it is called from a {@code @ModifyVariable} on {@code Connection#channelRead0},
     * so an exception thrown here propagates out of {@code channelRead0} into {@code
     * Connection#exceptionCaught}, which disconnects the player with "Internal Exception" --
     * the broken join this whole design exists to avoid, reached by accident. The concrete
     * route was real: a third-party {@code CustomQueryPayload} is free to return {@code null}
     * from {@code id()} (the record component is unvalidated), and {@code toString()} on that
     * null is an NPE. So the entire body sits in one {@code catch (RuntimeException)} that
     * substitutes the withheld-query packet, making the method total in the safe direction --
     * every path either passes the packet through or withholds it, and none throws.
     *
     * <p><b>The contrast with {@link #filterBundle} is deliberate.</b> That method rethrows,
     * on purpose, and is correct to: it is handed a bundle of arbitrary play-phase packets it
     * could not filter, and delivering one unfiltered is worse than losing the connection.
     * Here the trade runs the other way -- the packet in hand is a single login query whose
     * unfilterable form is already handled (it is withheld), so throwing would buy no privacy
     * at all and would cost a stalled or torn-down login. Do not "harmonise" the two.
     *
     * <p>Substituting under this mod's own sentinel id is not fabrication: nothing derived
     * from a {@code DiscardedQueryPayload}'s id is ever written to a socket, because the only
     * thing sent in response is {@code (transactionId, null)} and the answer payload interface
     * has no identifier in it. The id is a local dispatch marker; the wire sees a varint and a
     * boolean.
     *
     * <p><b>Singleplayer never reaches this method.</b> A local world's connection is
     * exempted at the call site, in {@code ConnectionMixin#cmdguard$forceVanillaLoginAnswer},
     * via {@code Connection#isMemoryConnection()} -- the login phase runs on the globals-only
     * snapshot, which by construction has no server key and so cannot apply {@link
     * #snapshotFor}'s {@link #SINGLEPLAYER_KEY} exemption. See that method's Javadoc.
     */
    public static Packet<?> forceVanillaLoginAnswer(ClientboundCustomQueryPacket packet, Snapshot snapshot) {
        try {
            if (!snapshot.active() || !snapshot.filterLogin()) {
                return packet;
            }
            CustomQueryPayload payload = packet.payload();
            if (payload instanceof DiscardedQueryPayload) {
                // Already unreachable by any mod handler -- vanilla decoded it as unknown and
                // will answer null on its own. Nothing to withhold, and claiming otherwise in
                // the log would be false. With Fabric API installed this branch is dead (its
                // readPayload mixin is unconditional); it exists so that a future Fabric that
                // made that mixin conditional degrades to vanilla behaviour rather than to a
                // spurious warning.
                return packet;
            }
            // Deliberately inside the try: CustomQueryPayload#id() is an interface call into
            // whatever third party implemented it, its record component is unvalidated and may
            // be null, and toString() on a null id is the concrete NPE this catch exists for.
            Identifier channel = payload.id();
            String id = channel.toString();
            if (!LoginQueryFilter.withholds(id, snapshot.active(), snapshot.filterLogin(),
                    snapshot.policy())) {
                return packet;
            }
            warnLoginWithheld(id, snapshot.policy());
            return withheldQuery(packet, channel);
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] login-query filtering failed; withholding the query", e);
            return withheldQuery(packet, WITHHELD_QUERY_MARKER);
        }
    }

    /**
     * The substituted packet: same transaction id, payload replaced by the one type vanilla
     * itself produces for a channel it does not recognise.
     */
    private static Packet<?> withheldQuery(ClientboundCustomQueryPacket packet, Identifier channel) {
        return new ClientboundCustomQueryPacket(packet.transactionId(), new DiscardedQueryPayload(channel));
    }

    /**
     * The one line that makes a broken join diagnosable. WARN, not INFO, because unlike every
     * other withhold in this class the consequence can be that the player cannot join at all,
     * and the disconnect they see comes from the server with no mention of CmdGuard on it.
     */
    private static void warnLoginWithheld(String channel, ExposurePolicy policy) {
        CmdGuardClient.LOGGER.warn(
                "[cmdguard] withheld the login-phase query on channel {}. Vanilla's empty answer"
                        + " was sent in its place, so no mod answered it. If this server now"
                        + " refuses the join, this is why: run \"{}\" and reconnect. The"
                        + " per-server form (\"/cmdguard expose <namespace>\") cannot help here"
                        + " -- the login phase runs before this connection has a server"
                        + " identity, so only global grants are in force.",
                channel, LoginQueryFilter.remedyCommand(channel, policy));
    }

    /**
     * {@link #allowInbound} for a sub-packet, with the channel-id read itself guarded.
     *
     * <p>{@code allowInbound} can only fail closed once it has an {@code Identifier}; reading
     * one out of a payload is an interface call into whatever implemented {@code
     * CustomPacketPayload}, so it is the one step in {@link #filterBundle}'s loop that could
     * throw. Treating a payload whose own channel cannot be read as withheld keeps the loop
     * total and is the correct direction: the packet dropped here is a custom payload, which
     * is the only kind of sub-packet this filter is ever allowed to drop.
     */
    private static boolean allowBundledPayload(ClientboundCustomPayloadPacket custom, Snapshot snapshot) {
        Identifier channel;
        try {
            channel = custom.payload().type().id();
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] could not read a bundled payload's channel, withholding it", e);
            return false;
        }
        return allowInbound(channel, snapshot, "inbound (bundled)");
    }

    private static String channelOf(ServerboundCustomPayloadPacket packet) {
        return packet.payload().type().id().toString();
    }

    /**
     * One INFO line the first time a channel is withheld on a connection.
     *
     * <p>Nothing about a withheld payload used to be logged anywhere, which made a working
     * filter and a filter that never ran indistinguishable from the outside -- the exact
     * condition under which the inbound filter's mixin-ordering defect went unnoticed. It
     * also matters for the user-facing case the spec calls out: the most common way anyone
     * meets this feature is being kicked by a server that required a mod they withheld, and
     * a kick leaves no chat to write to. {@code latest.log} is the one surface that survives
     * it.
     *
     * <p>Once per channel, not once per payload: a chatty channel would otherwise flood the
     * log, and the repeat count is already in the ledger and in {@code /cmdguard exposure}.
     * The ledger keys on the channel alone, so a channel withheld in both directions is
     * logged once, under whichever direction hit it first -- the {@code direction} word says
     * which that was, and is not a promise of one line per direction.
     */
    private static void logFirstWithhold(String direction, String channel, Snapshot snapshot) {
        CmdGuardClient.LOGGER.info(
                "[cmdguard] withheld {} payload on channel {} ({}); repeats are counted, not logged."
                        + " Run /cmdguard exposure for the full readout.",
                direction, channel, describeKey(snapshot.serverKey()));
    }

    /** How a snapshot's server key reads in a log line; {@code null} means no per-server identity. */
    private static String describeKey(String serverKey) {
        return serverKey == null ? "no per-server identity" : "server " + serverKey;
    }
}
