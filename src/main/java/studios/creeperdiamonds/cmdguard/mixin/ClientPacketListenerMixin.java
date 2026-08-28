package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.OutboundGuard;

/**
 * The only two places the client turns typed or clicked text into an outbound command.
 * Verified against Mojang's published 1.21.11 mappings:
 *   void sendCommand(java.lang.String)
 *   void sendUnattendedCommand(java.lang.String, net.minecraft.client.gui.screens.Screen)
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void cmdguard$guardTypedCommand(String command, CallbackInfo ci) {
        if (OutboundGuard.shouldBlock(command, false)) {
            OutboundGuard.reportBlocked(command);
            ci.cancel();
        }
    }

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void cmdguard$guardClickedCommand(String command, Screen screen, CallbackInfo ci) {
        if (OutboundGuard.shouldBlock(command, true)) {
            OutboundGuard.reportBlocked(command);
            ci.cancel();
        }
    }
}
