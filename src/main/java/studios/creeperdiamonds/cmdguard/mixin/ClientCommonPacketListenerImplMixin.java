package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
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
 * Inbound half of the exposure layer, plus wiring the per-connection snapshot into
 * existence: a mod that never receives the probe cannot answer it, and a connection that
 * never gets a snapshot can only ever run under the globals-only fallback.
 *
 * <h2>{@code handleCustomPayload} -- the drop</h2>
 *
 * <p>The full descriptor is mandatory. This class declares two methods named
 * {@code handleCustomPayload} that differ only in parameter type, and a bare method name
 * would bind the wrong one. See {@code NOTES.md}, "Inbound: the client custom-payload
 * handler".
 *
 * <p>This injection runs OFF the client thread. Vanilla returns early for {@code
 * DiscardedPayload} before it calls {@code PacketUtils.ensureRunningOnSameThread}, so a
 * {@code HEAD} injection precedes the thread hand-off. Drop only -- no client-world work
 * here, and no {@code Minecraft} access. The snapshot is read off the connection itself
 * (frozen there by Task 7's fix, populated by {@code cmdguard$beginExposure} below), never
 * recomputed via {@code ExposureGuard.snapshotFor(...)} -- that would require re-deriving a
 * server key off-thread, exactly the defect this design eliminates.
 *
 * <h2>{@code <init>} -- wiring {@code ExposureGuard.beginConnection}</h2>
 *
 * <p>The constructor runs for both {@code ClientConfigurationPacketListenerImpl} and {@code
 * ClientPacketListener} (both call this base-class constructor via {@code super(...)}), so
 * one injection covers both phases. Verified against the decompiled 1.21.11 source that
 * this constructor does <em>not</em> reliably run on the client thread: of its three call
 * sites, only two (play-phase construction in {@code
 * ClientConfigurationPacketListenerImpl#handleConfigurationFinished}, and mid-game
 * reconfiguration in {@code ClientPacketListener#handleConfigurationStart}) are preceded by
 * {@code PacketUtils.ensureRunningOnSameThread}; the initial configuration-phase
 * construction, from {@code ClientHandshakePacketListenerImpl#handleLoginFinished}, has no
 * such call anywhere in that method and is reached straight from {@code
 * Connection#channelRead0} -- the netty event loop.
 *
 * <p>That used to make this hook unsafe for initialising the snapshot, because the
 * original plan re-derived the server key here via {@code
 * Minecraft.getCurrentServer()}. It no longer needs to: {@code ExposureGuard.
 * beginConnection(Connection)} now only reads its own {@code AtomicReference}, populated
 * earlier -- on the client thread, before this constructor ever runs -- by {@code
 * ConnectScreenMixin}. Touching no {@code Minecraft} state here means it is safe to call
 * from whichever thread this constructor happens to run on.
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

    @Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/network/Connection;"
            + "Lnet/minecraft/client/multiplayer/CommonListenerCookie;)V",
            at = @At("TAIL"))
    private void cmdguard$beginExposure(Minecraft minecraft, Connection connection,
                                         CommonListenerCookie commonListenerCookie, CallbackInfo ci) {
        ExposureGuard.beginConnection(this.connection);
    }
}
