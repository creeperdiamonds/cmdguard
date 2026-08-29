package studios.creeperdiamonds.cmdguard.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The outbound choke point.
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
 * <p><b>Per-connection snapshot.</b> {@code sendPacket} provably runs on two threads (the
 * client thread via {@code ClientCommonPacketListenerImpl#send}, and the netty event loop
 * via the {@code runOnceConnected} lambda and the {@code pendingActions} drain), and a
 * single {@code Connection} can outlive config edits made mid-session. Rather than reading
 * shared, mutable state on every packet -- which either races or lets a mid-session toggle
 * apply inconsistently within one connection -- this mixin holds the whole decision surface
 * ({@link ExposureGuard.Snapshot}) as a field on the {@code Connection} instance itself. A
 * new connection is a new {@code Connection} object with a fresh, {@code null} field, so
 * there is no shared cell a stale write from a previous connection could land in: the
 * cross-connection leak a static holder was vulnerable to is structurally impossible here.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin implements ExposureGuard.ConnectionInit {

    @Shadow
    public abstract PacketFlow getSending();

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
     */
    @Unique
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
     */
    @Unique
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

    @ModifyVariable(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> cmdguard$filterIdentifiers(Packet<?> packet) {
        if (getSending() != PacketFlow.SERVERBOUND) {
            return packet;
        }
        return ExposureGuard.rewriteOrSame(packet, cmdguard$snapshot());
    }
}
