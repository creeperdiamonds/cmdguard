package studios.creeperdiamonds.cmdguard.mixin;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.OutboundGuard;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Both choke points -- outbound and inbound.
 *
 * <p>Targets {@code Connection#sendPacket}, not any of the three public {@code send}
 * overloads. All three funnel into {@code sendPacket}, but {@code sendPacket} also has a
 * caller that reaches it without going through a public {@code send} -- the
 * {@code runOnceConnected} lambda inside {@code initiateServerboundConnection}, which sends
 * the handshake packet directly. Hooking a public {@code send} would leave that path
 * unfiltered; {@code sendPacket} is the one place every outbound packet is provably
 * guaranteed to pass. See {@code NOTES.md}, "Verified mappings, 1.21.11".
 *
 * <p>Deliberately {@code Connection} rather than the client packet listener: a mod holding
 * the Connection can build a payload packet and send it directly, skipping the listener
 * entirely -- and a mod written to answer server probes is exactly the kind that would.
 * Hooking here also covers the configuration phase, where every Fabric API
 * client-to-server payload lives.
 *
 * <p>Do not move this down to {@code doSendPacket}: it sits after the deferred-queue split
 * and may already be running on the netty event loop, which is the wrong place to cancel
 * or swap a packet.
 *
 * <p><b>The inbound choke point lives here for the same class of reason.</b> The inbound
 * filter used to sit at {@code HEAD} of {@code
 * ClientCommonPacketListenerImpl#handleCustomPayload(ClientboundCustomPayloadPacket)}.
 * Fabric API 0.141.6's own {@code
 * net.fabricmc.fabric.mixin.networking.client.ClientCommonPacketListenerImplMixin} injects
 * at {@code HEAD} of that exact method with that exact descriptor, and cancels whenever
 * {@code ClientPlayNetworkAddon}/{@code ClientConfigurationNetworkAddon}{@code .handle}
 * returns true -- which is precisely when a client mod has a registered receiver for that
 * channel, i.e. exactly the set of payloads the inbound filter exists to block. Neither
 * mixin declared a priority, both defaulted to 1000, and {@code @Inject(order = ...)} does
 * not sort across mods: whichever callback the mixin processor happened to order first won,
 * and if Fabric's won, this mod's inbound filtering never ran at all. Pinning a priority
 * would only be a bet on a number. Moving the filter to {@code Connection#channelRead0}
 * removes the race instead of trying to win it -- {@code Connection} is installed as the
 * terminal {@code "packet_handler"} in the netty pipeline (see {@code
 * Connection#configurePacketHandler}), so {@code channelRead0} is where every decoded
 * inbound packet in every protocol phase arrives, strictly before any {@code PacketListener}
 * -- Fabric's addon included -- is handed it. Fabric API mixes into {@code Connection} too
 * but touches no inbound handler method, so there is nothing here to race with. This is the
 * same argument that put the outbound filter on {@code sendPacket} rather than on a public
 * {@code send} overload.
 *
 * <p><b>Two outbound concerns, not one.</b> This class began as the exposure layer's choke
 * point, but {@code sendPacket} is where <em>every</em> serverbound packet passes, so the
 * command guard's tab-completion half lives here too -- a
 * {@code ServerboundCommandSuggestionPacket} carries the partial command and reaches this
 * method through {@code ClientPacketListener#send}. It is judged by the command allowlist, not
 * by the exposure policy; the two never consult each other. See
 * {@link #cmdguard$guardSuggestionRequest}.
 *
 * <p><b>Three inbound handlers, not one</b>, because "one pipeline message" is not "one
 * packet of one type": a bare {@code ClientboundCustomPayloadPacket} is cancelled, a
 * {@code ClientboundBundlePacket} is rebuilt without its withheld sub-payloads, and a
 * login-phase {@code ClientboundCustomQueryPacket} has its payload substituted so that
 * vanilla, rather than a mod, answers it. See each handler's own Javadoc.
 *
 * <p><b>Per-connection snapshot.</b> {@code sendPacket} provably runs on two threads (the
 * client thread via {@code ClientCommonPacketListenerImpl#send}, and the netty event loop
 * via the {@code runOnceConnected} lambda and the {@code pendingActions} drain), and a
 * single {@code Connection} can outlive config edits made mid-session. Rather than reading
 * shared, mutable state on every packet -- which either races or lets a mid-session toggle
 * apply inconsistently within one connection -- this mixin holds the whole decision surface
 * ({@link ExposureGuard.Snapshot}) as a field on the {@code Connection} instance itself. A
 * new connection is a new {@code Connection} object whose {@code AtomicReference} holds
 * {@code null}, so there is no shared cell a stale write from a previous connection could
 * land in: the cross-connection leak a static holder was vulnerable to is structurally
 * impossible here.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin implements ExposureGuard.ConnectionInit {

    @Shadow
    public abstract PacketFlow getSending();

    @Shadow
    public abstract PacketFlow getReceiving();

    /**
     * True when this connection's netty channel is an in-process one -- i.e. the client
     * talking to its own integrated server.
     *
     * <p>Verified 2026-08-29 with {@code javap} against the mapped 1.21.11 merged jar: the
     * method is {@code public boolean isMemoryConnection()} and its body is exactly {@code
     * this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel}.
     * {@code Connection#connectToLocalServer} bootstraps through {@code
     * EventLoopGroupHolder.local()}, whose channel class is {@code LocalChannel} (also read
     * off the bytecode), so a local world's connection is exactly the set this returns true
     * for. A real socket connection -- including <em>joining</em> someone else's LAN game --
     * goes through {@code connectToServer} and a {@code NioSocketChannel}, so it returns
     * false. See {@link #cmdguard$forceVanillaLoginAnswer}.
     */
    @Shadow
    public abstract boolean isMemoryConnection();

    /**
     * The real, per-server decision surface for this connection, once installed. Written
     * ONLY by {@link #cmdguard$initExposure} via {@link AtomicReference#compareAndSet}, and
     * only ever transitions {@code null -> non-null}, exactly once, for the lifetime of this
     * {@code Connection} instance. Nothing else may write to this field -- in particular,
     * {@link #cmdguard$snapshot()} below must never write here, only read.
     *
     * <p>This split (this field vs. {@link #cmdguard$fallback}) exists because of a defect
     * the previous, single-field version had: the first outbound packet on every connection
     * is the handshake, sent through {@code sendPacket} directly from {@code
     * initiateServerboundConnection}'s {@code runOnceConnected} lambda -- before {@code
     * ClientHandshakePacketListenerImpl} exists, which does not extend {@code
     * ClientCommonPacketListenerImpl} and so triggers no {@code beginConnection} call at
     * all. That packet's read of {@code cmdguard$snapshot()} used to lazily install the
     * globals-only fallback into this very field; by the time the real
     * {@code cmdguard$initExposure} call happened (several packets later, from {@code
     * ClientCommonPacketListenerImpl}'s constructor), the field was already non-null, so the
     * idempotence guard rejected the real install outright -- on every connection,
     * singleplayer and multiplayer alike. Splitting the fields makes that structurally
     * impossible: a read can populate {@link #cmdguard$fallback} as many times as it likes
     * and can never touch this field.
     */
    @Unique
    private final AtomicReference<ExposureGuard.Snapshot> cmdguard$snapshot = new AtomicReference<>();

    /**
     * The globals-only snapshot returned by {@link #cmdguard$snapshot()} for the window
     * before {@link #cmdguard$initExposure} has installed the real one -- lazily computed
     * and cached here, entirely separate from {@link #cmdguard$snapshot}. {@code volatile}
     * for the same cross-thread visibility reason as that field; a benign race where two
     * threads both compute and cache their own fallback before either wins is tolerated,
     * since both read the same (for the duration of the race, unchanging) global config and
     * neither is ever more permissive than the other -- see {@link #cmdguard$snapshot()}.
     */
    @Unique
    private volatile ExposureGuard.Snapshot cmdguard$fallback;

    /**
     * Called by the connection lifecycle as soon as the server key for this connection is
     * known and before any packet can be sent on it. Safe to never call: {@link
     * #cmdguard$snapshot()} below falls back to a globals-only snapshot on first use if this
     * was skipped or lost the race with the first packet.
     *
     * <p>Idempotent, and now a genuine compare-and-set rather than a volatile
     * check-then-act: {@link #cmdguard$snapshot} starts {@code null} and this method installs
     * {@code snapshot} only via {@link AtomicReference#compareAndSet(Object, Object)
     * compareAndSet(null, snapshot)}, so exactly one caller among any number of concurrently
     * racing callers ever succeeds, for the entire lifetime of this connection -- this is not
     * merely "usually true because construction is sequential"; it holds regardless of
     * ordering or thread. This matters because {@code ClientCommonPacketListenerImpl}'s
     * shared constructor runs more than once against this same {@code Connection} instance
     * -- once for the configuration-phase listener, again for the play-phase listener, and a
     * third time on a mid-game reconfigure -- and {@code beginConnection} is reachable from
     * the netty event loop, so nothing guarantees those calls are ordered in practice. Only
     * the winning call may decide this connection's snapshot. See {@code
     * ExposureGuard.beginConnection}'s Javadoc for why a second, silently-accepted call here
     * used to be a real leak (it would replace a real per-server snapshot with a
     * {@code "singleplayer"} fallback for the rest of the connection's life).
     *
     * <p>Deliberately <em>not</em> {@code @Unique}. On a non-private method that annotation
     * means "discard this method if another mixin already added one with the same name and
     * descriptor" -- and a discarded {@code cmdguard$initExposure} would leave {@code
     * Connection} declaring {@link ExposureGuard.ConnectionInit} with no implementation of
     * it, i.e. an {@code AbstractMethodError} on the first call. The {@code cmdguard$}
     * prefix makes a collision essentially impossible, so this was only ever theoretical;
     * dropping the annotation removes the failure mode outright.
     */
    @Override
    public boolean cmdguard$initExposure(ExposureGuard.Snapshot snapshot) {
        return cmdguard$snapshot.compareAndSet(null, snapshot);
    }

    /**
     * Returns this connection's snapshot: the real one from {@link #cmdguard$snapshot} if
     * {@link #cmdguard$initExposure} has installed it, otherwise a globals-only fallback
     * computed (and cached, for later calls) into the entirely separate {@link
     * #cmdguard$fallback} field. This method only ever reads {@link #cmdguard$snapshot}, via
     * {@link AtomicReference#get()} -- it must never write to it, since that cell exists
     * solely to record whether the real, one-time install has happened.
     *
     * <p>Not {@code @Unique}, for the same reason as {@link #cmdguard$initExposure}.
     */
    @Override
    public ExposureGuard.Snapshot cmdguard$snapshot() {
        ExposureGuard.Snapshot installed = cmdguard$snapshot.get();
        if (installed != null) {
            return installed;
        }
        ExposureGuard.Snapshot fallback = cmdguard$fallback;
        if (fallback == null) {
            fallback = ExposureGuard.globalsOnlySnapshot();
            cmdguard$fallback = fallback;
        }
        return fallback;
    }

    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheld(Packet<?> packet,
                                       ChannelFutureListener listener,
                                       boolean flush,
                                       CallbackInfo ci) {
        if (getSending() == PacketFlow.SERVERBOUND
                && ExposureGuard.shouldDrop(packet, cmdguard$snapshot())) {
            ci.cancel();
        }
    }

    /**
     * The command guard's second half: tab completion, which leaks the same text one step
     * earlier than running the command does.
     *
     * <p>{@code ClientPacketListenerMixin} guards {@code sendCommand} and {@code
     * sendUnattendedCommand}, so a command whose root is not allowlisted never leaves the
     * client. But pressing Tab sends the partial text first:
     * {@code ClientSuggestionProvider#customSuggestion} does
     * {@code this.connection.send(new ServerboundCommandSuggestionPacket(i,
     * commandContext.getInput()))}, and {@code ClientCommonPacketListenerImpl#send(Packet)} is
     * a one-line {@code this.connection.send(packet)} into {@code Connection#send}, which
     * calls {@code sendPacket}. So the request already passes through this very choke point
     * and the guard needs no mixin of its own -- only this handler. Verified 2026-08-29
     * against the decompiled 1.21.11 sources and, for the accessors, {@code javap} over the
     * mapped merged jar; see {@code NOTES.md}, "Outbound: tab-completion requests".
     *
     * <p>The decision is {@link OutboundGuard#shouldBlockSuggestion}, which defers to the
     * Minecraft-free, unit-tested {@code SuggestionFilter}. It reuses the command allowlist:
     * a completion request is judged by the same rule as the command it would become.
     *
     * <p><b>Cancelling is safe, and this was read rather than assumed.</b> {@code
     * customSuggestion} has already created {@code pendingSuggestionsFuture} and returned it
     * to {@code CommandSuggestions}, which stores it in {@code pendingSuggestions}. Every read
     * of that field in {@code CommandSuggestions} is guarded by {@code isDone()} -- the render
     * path checks it explicitly, and {@code updateUsageInfo} (the only other {@code join()})
     * runs solely from the {@code thenRun} callback -- so a future that never completes leaves
     * the suggestion popup empty and nothing else. The next keystroke's {@code
     * customSuggestion} call cancels it outright. No hang, no leak, no exception.
     *
     * <p>Fail closed: an exception while deciding cancels the send rather than letting the
     * request out. The {@code instanceof} and the flow check sit outside the {@code try} on
     * purpose, exactly as in {@link #cmdguard$dropWithheldInbound}, so a fail-closed cancel
     * can only ever drop a suggestion request and never some unrelated packet.
     *
     * <p>This and {@link #cmdguard$dropWithheld} both attach at {@code HEAD} of {@code
     * sendPacket} and their relative order is undefined, which is fine because they match
     * disjoint packet types: a {@code ServerboundCommandSuggestionPacket} is not a {@code
     * ServerboundCustomPayloadPacket}. Same pairing argument as the three inbound handlers.
     */
    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$guardSuggestionRequest(Packet<?> packet,
                                                 ChannelFutureListener listener,
                                                 boolean flush,
                                                 CallbackInfo ci) {
        if (getSending() != PacketFlow.SERVERBOUND
                || !(packet instanceof ServerboundCommandSuggestionPacket suggestion)) {
            return;
        }
        try {
            if (OutboundGuard.shouldBlockSuggestion(suggestion.getCommand())) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error(
                    "[cmdguard] suggestion guard check failed, withholding the request", e);
            ci.cancel();
        }
    }

    @ModifyVariable(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> cmdguard$filterIdentifiers(Packet<?> packet) {
        if (getSending() != PacketFlow.SERVERBOUND) {
            return packet;
        }
        return ExposureGuard.rewriteOrSame(packet, cmdguard$snapshot());
    }

    /**
     * The inbound choke point: a mod that never receives the probe cannot answer it.
     *
     * <p>Runs on the netty event loop, before {@code channelRead0} consults {@code
     * packetListener} at all -- so before Fabric API's addon dispatch, before vanilla's
     * {@code DiscardedPayload} early-out, and before {@code
     * PacketUtils.ensureRunningOnSameThread}. Do no client-world work here; the body only
     * reads this connection's own frozen snapshot and an {@code Identifier}, and drops.
     *
     * <p>Cancelling here simply means the packet is never handed to the listener -- exactly
     * what vanilla itself does for a payload whose channel has no receiver, which arrives as
     * a {@code DiscardedPayload} and is dropped a few lines further down. The only other
     * effect of cancelling is that {@code receivedPackets} is not incremented for the
     * dropped packet, which is a debug counter.
     *
     * <p>{@code Connection} is shared by both sides, so the flow is checked: only a
     * connection that <em>receives</em> clientbound traffic is a client connection. (A
     * {@code ClientboundCustomPayloadPacket} could not reach a serverbound-receiving
     * connection anyway; the check is the same belt-and-braces as the outbound side's.)
     *
     * <p>Fail closed: any {@code RuntimeException} in here withholds rather than propagating
     * onto the netty loop and tearing the connection down.
     */
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheldInbound(ChannelHandlerContext context,
                                              Packet<?> packet,
                                              CallbackInfo ci) {
        // Outside the catch on purpose: neither a field read nor an instanceof can throw,
        // and a fail-closed cancel must only ever be able to drop a custom payload -- never
        // some unrelated packet this filter has no business touching.
        if (getReceiving() != PacketFlow.CLIENTBOUND
                || !(packet instanceof ClientboundCustomPayloadPacket custom)) {
            return;
        }
        try {
            if (!ExposureGuard.allowInbound(custom.payload().type().id(), cmdguard$snapshot())) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] inbound drop check failed, withholding", e);
            ci.cancel();
        }
    }

    /**
     * The second half of the inbound choke point: a play-phase custom payload does not have
     * to arrive as its own pipeline message.
     *
     * <p>{@link #cmdguard$dropWithheldInbound} above matches on the message {@code
     * channelRead0} is handed, and that is enough only while one pipeline message means one
     * packet. In the play protocol it does not. Verified 2026-08-29 against the decompiled
     * 1.21.11 sources and the mapped merged jar (see {@code NOTES.md}, "Inbound: packet
     * bundles"): the play clientbound protocol is the only one whose {@code ProtocolInfo}
     * carries a {@code BundlerInfo}, so {@code Connection#setupInboundProtocol} installs a
     * {@code PacketBundlePacker} as {@code "bundler"} immediately after {@code "decoder"} --
     * upstream of the {@code "packet_handler"} that is this {@code Connection} -- and that
     * handler replaces everything between two {@code ClientboundBundleDelimiterPacket}s with
     * one {@code ClientboundBundlePacket}. The sub-packets never arrive here individually;
     * {@code ClientPacketListener#handleBundlePacket} calls {@code subPacket.handle(this)} on
     * each of them later, on the client thread, which for a custom payload is
     * {@code handleCustomPayload} and hence Fabric API's addon dispatch. Matching only the
     * bare packet left a bundled payload completely unfiltered.
     *
     * <p>A {@code @ModifyVariable} rather than a cancel, deliberately: the bundle is mostly
     * entity traffic and cancelling it would drop packets this mod has no business touching.
     * {@link ExposureGuard#filterBundle} rebuilds the bundle from a subset of its own
     * sub-packets -- removal only, never an addition, never a non-payload sub-packet -- and
     * returns the original object untouched when nothing was withheld.
     *
     * <p>This and {@link #cmdguard$dropWithheldInbound} both attach at {@code HEAD} and their
     * relative order is not defined, which is fine because they match disjoint packet types:
     * a {@code ClientboundBundlePacket} is never a {@code ClientboundCustomPayloadPacket}.
     * Same pairing as the outbound {@code sendPacket} handlers above.
     */
    @ModifyVariable(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> cmdguard$filterBundledPayloads(Packet<?> packet) {
        if (getReceiving() != PacketFlow.CLIENTBOUND
                || !(packet instanceof ClientboundBundlePacket bundle)) {
            return packet;
        }
        return ExposureGuard.filterBundle(bundle, cmdguard$snapshot());
    }

    /**
     * The third half of the inbound choke point: the login phase, which is neither a custom
     * payload nor a bundle.
     *
     * <p>A login query arrives as a {@code ClientboundCustomQueryPacket} -- a different packet
     * type from {@code ClientboundCustomPayloadPacket}, so neither handler above matches it,
     * which is why the login phase was uncovered until now. It is also never bundled: {@code
     * ProtocolInfoBuilder#withBundlePacket} has exactly one caller in the whole game
     * ({@code GameProtocols}, play clientbound) and {@code ProtocolInfo#bundlerInfo()} is
     * {@code @Nullable}, so the login protocol installs no {@code "bundler"} handler at all
     * and a login query is always its own top-level pipeline message. Verified 2026-08-29
     * over the full decompiled 1.21.11 extract; see {@code NOTES.md}, "The login phase".
     *
     * <p><b>A substitution, not a cancel, and this is the load-bearing decision.</b>
     * Cancelling here would not withhold, it would stall: vanilla keeps no per-transaction
     * accounting on either side, so a querying server -- which is blocked on that transaction
     * and therefore sends nothing while it waits -- leaves the client receiving nothing until
     * {@code Connection}'s own {@code ReadTimeoutHandler(30)} fires {@code disconnect.timeout}.
     * A hang is a behaviour no vanilla client exhibits, so it would disclose more than the
     * answer it was meant to avoid. Replacing the payload with a {@code DiscardedQueryPayload}
     * instead makes Fabric API's {@code ClientHandshakePacketListenerImplMixin} -- which only
     * intercepts a {@code PacketByteBufLoginQueryRequestPayload} -- skip the packet, leaving
     * unmodified vanilla {@code handleCustomQuery} to send its unconditional
     * {@code (transactionId, null)} answer. See {@link ExposureGuard#forceVanillaLoginAnswer}
     * for the full argument and the {@code javap} verification, and
     * {@code .superpowers/sdd/login-phase-spike.md} for the investigation.
     *
     * <p>Unlike the play-phase mixin-ordering hazard that moved the inbound filter here in the
     * first place, this does not race Fabric's mixin: the substitution happens in {@code
     * channelRead0}, before {@code genericsFtw} dispatches to the listener at all, so Fabric's
     * injection inside {@code handleCustomQuery} is downstream by construction rather than by
     * priority. Fabric API's own {@code ConnectionMixin} still touches no inbound handler.
     *
     * <p>This and the two handlers above all attach at {@code HEAD} and their relative order is
     * undefined, which is again fine because all three match mutually disjoint packet types: a
     * {@code ClientboundCustomQueryPacket} is neither a {@code ClientboundBundlePacket} nor a
     * {@code ClientboundCustomPayloadPacket}, and each returns its argument untouched when it
     * does not match.
     *
     * <p><b>The snapshot here is always the globals-only one, necessarily.</b> {@code
     * ExposureGuard.beginConnection} runs from {@code ClientCommonPacketListenerImpl}'s
     * constructor, first reached at {@code handleLoginFinished} -- after the login phase. So
     * {@link #cmdguard$snapshot()} returns {@link ExposureGuard#globalsOnlySnapshot()} for
     * every login query, and per-server grants cannot apply. That is documented in the WARN
     * line this emits, which names {@code /cmdguard expose global <namespace>} as the remedy.
     *
     * <p><b>Singleplayer is exempt here, and the exemption has to live in this method.</b>
     * {@code ExposureGuard.snapshotFor} switches {@code active} off for {@link
     * ExposureGuard#SINGLEPLAYER_KEY} -- a local world's connection runs between the client
     * and the integrated server in the same JVM, so there is no remote party and nothing to
     * withhold from. But the login phase necessarily runs on {@link
     * ExposureGuard#globalsOnlySnapshot()} (see the paragraph above), whose {@code active} is
     * plain {@code exposureActive()} with no singleplayer exemption in it -- and singleplayer
     * does run a full handshake through this very method, since {@code
     * Connection#connectToLocalServer} builds an ordinary {@code Connection} and the
     * integrated server runs the real login protocol over it. Without this check, a
     * server-side mod using {@code ServerLoginConnectionEvents.QUERY_START} on the
     * <em>integrated</em> server would have its own query withheld from the same process, for
     * zero privacy benefit -- and if that handshake is load-bearing, the local world fails to
     * load, with a WARN telling the player to expose a namespace to themselves.
     *
     * <p>{@link #isMemoryConnection()} is the right test and not merely a convenient one: it
     * is true for exactly the in-process channel, so a LAN <em>host</em> (which is still this
     * same integrated-server connection) is exempt and a LAN <em>join</em> (a real socket to
     * another machine) stays filtered -- matching the per-server behaviour {@code
     * snapshotFor} documents and the README describes. It also cannot throw or NPE: {@code
     * Connection#channel} is assigned in {@code channelActive}, which fires before any read,
     * and a null channel would fail the two {@code instanceof} tests and return false, i.e.
     * keep filtering on.
     */
    @ModifyVariable(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> cmdguard$forceVanillaLoginAnswer(Packet<?> packet) {
        if (getReceiving() != PacketFlow.CLIENTBOUND
                || !(packet instanceof ClientboundCustomQueryPacket query)
                || isMemoryConnection()) {
            return packet;
        }
        return ExposureGuard.forceVanillaLoginAnswer(query, cmdguard$snapshot());
    }
}
