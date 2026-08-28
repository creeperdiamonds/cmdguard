package studios.creeperdiamonds.cmdguard;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The decision: does this command leave the machine?
 *
 * <p>Anything reaching ClientPacketListener#sendCommand has already been offered to
 * Fabric's client dispatcher, so a command handled locally normally never gets here.
 * We re-check the dispatcher anyway via the public getActiveDispatcher() so the result
 * does not depend on mixin injection order.
 */
public final class OutboundGuard {
    private OutboundGuard() {
    }

    public static boolean shouldBlock(String command, boolean clicked) {
        GuardConfig config = GuardConfig.get();
        if (!config.enabled) {
            return false;
        }

        String root = rootOf(command);
        if (root.isEmpty()) {
            return false;
        }

        // Owned by a client mod -- it never reaches the network. Never our business.
        if (isClientCommand(root)) {
            return false;
        }

        if (clicked && config.allowClickedCommands) {
            return false;
        }

        return !config.allowlist.contains(root);
    }

    public static boolean isClientCommand(String root) {
        CommandDispatcher<FabricClientCommandSource> dispatcher =
                ClientCommandManager.getActiveDispatcher();
        return dispatcher != null && dispatcher.getRoot().getChild(root) != null;
    }

    public static String rootOf(String command) {
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        int space = trimmed.indexOf(' ');
        String root = space < 0 ? trimmed : trimmed.substring(0, space);
        return root.toLowerCase(Locale.ROOT);
    }

    public static void reportBlocked(String command) {
        String root = rootOf(command);

        Component allow = Component.literal("[allow /" + root + "]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/cmdguard allow " + root)));

        Component message = Component.literal("CmdGuard blocked ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("/" + root).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" -- not on your allowlist. ").withStyle(ChatFormatting.GRAY))
                .append(allow);

        say(message);
    }

    public static void say(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage(message);
        }
    }
}
