package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

/**
 * Captures the connect target at the moment a multiplayer join begins -- before any {@code
 * Connection}, and hence before any {@code ClientCommonPacketListenerImpl}, exists.
 *
 * <p>{@code Minecraft#getCurrentServer()} cannot be used for this. In 1.21.11 it is:
 *
 * <pre>{@code
 * public @Nullable ServerData getCurrentServer() {
 *     return Optionull.map(this.getConnection(), ClientPacketListener::getServerData);
 * }
 * }</pre>
 *
 * <p>{@code getConnection()} returns the <em>play</em>-phase listener, which does not exist
 * for the entire configuration phase -- exactly when every Fabric API client-to-server
 * payload is sent (accepted_attachments, recipe serializers, custom ingredients,
 * {@code c:register}). Deriving the server key from that method at any point during
 * configuration silently returns {@code null}, which {@code ExposureGuard} would have
 * turned into {@code "singleplayer"} for a real server -- meaning per-server grants would
 * never apply to the traffic this feature exists to filter.
 *
 * <p>{@code startConnecting} is static, runs on the client thread (it calls {@code
 * minecraft.setScreen(connectScreen)} directly), and returns before the socket opens --
 * the actual connection attempt happens afterwards on a separate "Server Connector #N"
 * thread. That makes this the correct, and only, place to learn the server key on the
 * client thread before a {@code Connection} object exists to hang a snapshot off of.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;"
            + "Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
            + "Lnet/minecraft/client/multiplayer/ServerData;Z"
            + "Lnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"))
    private static void cmdguard$rememberServerKey(Screen screen, Minecraft minecraft, ServerAddress serverAddress,
                                                    ServerData serverData, boolean quickPlay,
                                                    TransferState transferState, CallbackInfo ci) {
        ExposureGuard.rememberServerKey(serverData.ip);
    }
}
