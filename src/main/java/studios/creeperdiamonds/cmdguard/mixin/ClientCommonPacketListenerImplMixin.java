package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

/**
 * Inbound half of the exposure layer: a mod that never receives the probe cannot answer it.
 *
 * <p>The full descriptor is mandatory. This class declares two methods named
 * {@code handleCustomPayload} that differ only in parameter type, and a bare method name
 * would bind the wrong one. See {@code NOTES.md}, "Inbound: the client custom-payload
 * handler".
 *
 * <p>This runs OFF the client thread. Vanilla returns early for {@code DiscardedPayload}
 * before it calls {@code PacketUtils.ensureRunningOnSameThread}, so a {@code HEAD}
 * injection precedes the thread hand-off. Drop only -- no client-world work here, and no
 * {@code Minecraft} access. The snapshot is read off the connection itself (frozen there by
 * Task 7's fix), never recomputed via {@code ExposureGuard.snapshotFor(currentServerKey())}
 * -- that would reintroduce the exact off-thread {@code Minecraft.getCurrentServer()} read
 * this design eliminated.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {

    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheldInbound(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        ExposureGuard.Snapshot snapshot =
                ((ExposureGuard.ConnectionInit) (Object) this.connection).cmdguard$snapshot();

        if (!ExposureGuard.allowInbound(packet.payload().type().id(), snapshot)) {
            ci.cancel();
        }
    }
}
