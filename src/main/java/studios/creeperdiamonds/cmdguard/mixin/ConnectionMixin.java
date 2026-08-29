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
     * The whole outbound decision surface for this connection. {@code volatile} so a
     * {@link #cmdguard$initExposure} write on the client thread is visible to a
     * {@code sendPacket} call already running on the netty event loop, and vice versa.
     */
    @Unique
    private volatile ExposureGuard.Snapshot cmdguard$snapshot;

    /**
     * Called once by the connection lifecycle, on the client thread, as soon as the server
     * key for this connection is known and before any packet can be sent on it. Safe to
     * never call: {@link #cmdguard$snapshot()} below falls back to a globals-only snapshot
     * on first use if this was skipped or lost the race with the first packet.
     */
    @Unique
    @Override
    public void cmdguard$initExposure(String serverKey) {
        cmdguard$snapshot = ExposureGuard.snapshotFor(serverKey);
    }

    /**
     * Returns this connection's snapshot, computing a globals-only fallback on first use
     * if {@link #cmdguard$initExposure} never ran. Two threads racing here before init can
     * each compute and publish their own fallback snapshot -- harmless, since both read the
     * same (unchanging, for the duration of this race) global config and neither can ever
     * be more permissive than the other; the field simply ends up holding whichever one
     * wrote last. What cannot happen is a *different connection's* snapshot ending up here,
     * because this field belongs to this Connection instance alone.
     */
    @Unique
    @Override
    public ExposureGuard.Snapshot cmdguard$snapshot() {
        ExposureGuard.Snapshot snapshot = cmdguard$snapshot;
        if (snapshot == null) {
            snapshot = ExposureGuard.globalsOnlySnapshot();
            cmdguard$snapshot = snapshot;
        }
        return snapshot;
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
