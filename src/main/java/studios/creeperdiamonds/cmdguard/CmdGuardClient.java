package studios.creeperdiamonds.cmdguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CmdGuardClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("cmdguard");

    @Override
    public void onInitializeClient() {
        GuardConfig.get(); // load / create config early

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                CmdGuardCommands.register(dispatcher));

        // When a server announces channels it accepts, flag any that a mod of yours
        // also handles -- that pairing is what makes a load-out probe possible.
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) ->
                ChannelAudit.onServerChannels(channels));

        LOGGER.info("[cmdguard] client-side outbound command guard active");
    }
}
