package studios.creeperdiamonds.cmdguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studios.creeperdiamonds.cmdguard.exposure.ChannelLedger;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

public final class CmdGuardClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("cmdguard");

    @Override
    public void onInitializeClient() {
        // Eagerly warm the config here, on the client thread, before any connection can
        // exist -- see GuardConfig.get()'s javadoc for why that matters to the netty-loop
        // readers on the filtering path.
        GuardConfig.get();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                CmdGuardCommands.register(dispatcher));

        // When a server announces channels it accepts, flag any that a mod of yours
        // also handles -- that pairing is what makes a load-out probe possible.
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) ->
                ChannelAudit.onServerChannels(channels));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reportExposureOnJoin());

        LOGGER.info("[cmdguard] client-side outbound command guard active");
    }

    /**
     * The join-time exposure line the spec asks for (design doc line 244).
     *
     * <p>By the time play begins, the configuration phase -- where every Fabric API
     * client-to-server payload lives, and therefore where most of the withholding happens --
     * is already over. Without this line the only trace of it is {@code latest.log} or a
     * command the player has no reason to run. Fires on {@code ClientPlayConnectionEvents.JOIN},
     * which is client-thread, so writing to the chat here is safe.
     */
    private static void reportExposureOnJoin() {
        ExposureGuard.Snapshot snapshot = ExposureGuard.currentSnapshot();

        if (snapshot != null && !snapshot.active()) {
            OutboundGuard.say(Component.literal("CmdGuard exposure filtering is ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("OFF").withStyle(ChatFormatting.RED))
                    .append(Component.literal(" for this connection -- nothing is being withheld.")
                            .withStyle(ChatFormatting.GRAY)));
            return;
        }

        ChannelLedger.Counts counts = ExposureGuard.ledger().counts();
        Component details = Component.literal("[/cmdguard exposure]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.RunCommand("/cmdguard exposure")));

        OutboundGuard.say(Component.literal("CmdGuard exposure: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(counts.exposed() + " exposed").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(counts.withheld() + " withheld").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" so far. ").withStyle(ChatFormatting.GRAY))
                .append(details));
    }
}
