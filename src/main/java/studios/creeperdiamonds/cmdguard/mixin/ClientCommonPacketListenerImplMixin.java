package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

import java.util.Locale;

/**
 * Inbound half of the exposure layer, plus wiring the per-connection snapshot into
 * existence and flagging server transfers: a mod that never receives the probe cannot
 * answer it, a connection that never gets a snapshot can only ever run under the
 * globals-only fallback, and a transferred-to server must not inherit the grants the
 * player made for the server that sent it there.
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
 * {@code HEAD} injection precedes the thread hand-off. Drop only -- there is no client-world
 * access anywhere on this path (the {@code Minecraft} import on this class is needed only
 * for the {@code <init>} injection's parameter type below, not touched here). The snapshot
 * is read off the connection itself (frozen there by Task 7's fix, populated by {@code
 * cmdguard$beginExposure} below), never recomputed via {@code
 * ExposureGuard.snapshotFor(...)} -- that would require re-deriving a server key off-thread.
 * The whole body is wrapped in a fail-closed {@code catch}: a {@code ClassCastException} on
 * the interface cast, or a throwing {@code payload().type().id()}, must withhold rather than
 * propagate onto the netty loop and tear down the connection.
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

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheldInbound(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            ExposureGuard.Snapshot snapshot =
                    ((ExposureGuard.ConnectionInit) (Object) this.connection).cmdguard$snapshot();

            if (!ExposureGuard.allowInbound(packet.payload().type().id(), snapshot)) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] inbound drop check failed, withholding", e);
            ci.cancel();
        }
    }

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
