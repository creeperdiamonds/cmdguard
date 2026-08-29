package studios.creeperdiamonds.cmdguard.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Shadow
    public abstract PacketFlow getSending();

    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheld(Packet<?> packet,
                                       ChannelFutureListener listener,
                                       boolean flush,
                                       CallbackInfo ci) {
        if (getSending() == PacketFlow.SERVERBOUND && ExposureGuard.shouldDrop(packet)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> cmdguard$filterIdentifiers(Packet<?> packet) {
        if (getSending() != PacketFlow.SERVERBOUND) {
            return packet;
        }
        return ExposureGuard.rewriteOrSame(packet);
    }
}
