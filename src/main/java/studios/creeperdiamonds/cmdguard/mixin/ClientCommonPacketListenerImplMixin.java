package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

import java.util.Locale;

/**
 * Connection-lifecycle wiring for the exposure layer: bringing the per-connection snapshot
 * into existence, and flagging server transfers. A connection that never gets a snapshot can
 * only ever run under the globals-only fallback, and a transferred-to server must not
 * inherit the grants the player made for the server that sent it there.
 *
 * <p><b>The inbound filter is deliberately not here.</b> It used to be, injected at {@code
 * HEAD} of {@code handleCustomPayload(ClientboundCustomPayloadPacket)} -- the same method,
 * with the same descriptor, that Fabric API's own {@code
 * net.fabricmc.fabric.mixin.networking.client.ClientCommonPacketListenerImplMixin} injects
 * at {@code HEAD} and cancels from whenever a client mod has a receiver registered for the
 * channel, which is exactly the set of payloads the filter exists to block. With no priority
 * declared on either side, whether this mod's filter ran at all came down to mixin ordering.
 * It now lives on {@code Connection#channelRead0}, upstream of every packet listener; see
 * {@code ConnectionMixin}'s class javadoc for the full argument.
 *
 * <h2>{@code <init>} -- wiring {@code ExposureGuard.beginConnection}</h2>
 *
 * <p>The constructor runs for both {@code ClientConfigurationPacketListenerImpl} and {@code
 * ClientPacketListener} (both call this base-class constructor via {@code super(...)}), and
 * -- confirmed by a code-review pass against the previous version of this fix -- it runs
 * <em>against the very same {@code Connection}</em> both times: {@code
 * ClientHandshakePacketListenerImpl#handleLoginFinished} builds the configuration listener,
 * and {@code ClientConfigurationPacketListenerImpl#handleConfigurationFinished} builds the
 * play listener, on the one {@code Connection} that survives the whole session (plus a
 * third construction on a mid-game reconfigure). {@code ExposureGuard.beginConnection} and
 * {@code ConnectionMixin#cmdguard$initExposure} are what make repeated calls for the same
 * connection safe: only the first actually installs a snapshot and resets the ledger.
 *
 * <p>Verified against the decompiled 1.21.11 source that this constructor does <em>not</em>
 * reliably run on the client thread: of its three call sites, only two (play-phase
 * construction in {@code ClientConfigurationPacketListenerImpl#handleConfigurationFinished},
 * and mid-game reconfiguration in {@code ClientPacketListener#handleConfigurationStart}) are
 * preceded by {@code PacketUtils.ensureRunningOnSameThread}; the initial configuration-phase
 * construction, from {@code ClientHandshakePacketListenerImpl#handleLoginFinished}, has no
 * such call anywhere in that method and is reached straight from {@code
 * Connection#channelRead0} -- the netty event loop. That is safe here because the server key
 * is read from {@code commonListenerCookie.serverData()} -- a plain, already-resolved field
 * on an immutable record parameter, not a re-derived read of live {@code Minecraft} state --
 * so this injection touches no {@code Minecraft} object at all and is correct on whichever
 * thread the constructor happens to run.
 *
 * <p>Deriving the key from the cookie's {@code ServerData} (rather than, as an earlier
 * version of this fix did, capturing it separately at {@code ConnectScreen} time) is also
 * what makes {@code beginConnection} correct for join paths that never go through {@code
 * ConnectScreen} at all -- Realms connections build their listener directly (see {@code
 * net.minecraft.realms.RealmsConnect#connect}), but still pass a real, correctly-addressed
 * {@code ServerData} into it, which flows into the cookie the same as any other join.
 *
 * <h2>{@code handleTransfer} -- flagging the one case the cookie gets wrong</h2>
 *
 * <p>{@code handleTransfer} calls {@code ConnectScreen.startConnecting(..., this.serverData,
 * ...)}, passing the <em>origin</em> server's {@code ServerData} into the connection being
 * opened to the <em>destination</em> server -- so the destination's cookie would otherwise
 * carry the origin's key. This injection sets a one-shot flag ({@code
 * ExposureGuard.markNextConnectionAsTransfer}) that the next {@code beginConnection} call
 * consumes to force the globals-only snapshot instead of trusting the inherited key. Runs
 * twice per real transfer for the same reason {@code handleCustomPayload} runs off-thread --
 * {@code handleTransfer} also calls {@code PacketUtils.ensureRunningOnSameThread}, which
 * re-dispatches the whole method onto the client thread and lets the netty-thread call
 * through to {@code HEAD} first -- but the flag is a plain {@code AtomicBoolean} set, so
 * running twice is harmless.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {

    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/network/Connection;"
            + "Lnet/minecraft/client/multiplayer/CommonListenerCookie;)V",
            at = @At("TAIL"))
    private void cmdguard$beginExposure(Minecraft minecraft, Connection connection,
                                         CommonListenerCookie commonListenerCookie, CallbackInfo ci) {
        ServerData serverData = commonListenerCookie.serverData();
        String serverKey = serverData == null || serverData.ip == null
                ? "singleplayer"
                : serverData.ip.toLowerCase(Locale.ROOT);
        ExposureGuard.beginConnection(this.connection, serverKey);
    }

    @Inject(method = "handleTransfer(Lnet/minecraft/network/protocol/common/ClientboundTransferPacket;)V",
            at = @At("HEAD"))
    private void cmdguard$markTransfer(ClientboundTransferPacket clientboundTransferPacket, CallbackInfo ci) {
        ExposureGuard.markNextConnectionAsTransfer();
    }
}
