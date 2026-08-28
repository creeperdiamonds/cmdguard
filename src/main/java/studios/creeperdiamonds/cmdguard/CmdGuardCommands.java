package studios.creeperdiamonds.cmdguard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * /cmdguard -- registered on the client dispatcher, so it is itself never sent anywhere.
 * Root literal is the mod id: a generic root would shadow a server command and also
 * fall through to the server on any boot where this mod is not loaded.
 */
public final class CmdGuardCommands {
    private CmdGuardCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("cmdguard")
                .executes(CmdGuardCommands::status)
                .then(ClientCommandManager.literal("status").executes(CmdGuardCommands::status))
                .then(ClientCommandManager.literal("on").executes(ctx -> setEnabled(ctx, true)))
                .then(ClientCommandManager.literal("off").executes(ctx -> setEnabled(ctx, false)))
                .then(ClientCommandManager.literal("list").executes(CmdGuardCommands::list))
                .then(ClientCommandManager.literal("audit").executes(ctx -> {
                    ChannelAudit.report();
                    return 1;
                }))
                .then(ClientCommandManager.literal("clear").executes(CmdGuardCommands::clear))
                .then(ClientCommandManager.literal("allow")
                        .then(ClientCommandManager.argument("root", StringArgumentType.word())
                                .executes(ctx -> allow(ctx, StringArgumentType.getString(ctx, "root")))))
                .then(ClientCommandManager.literal("deny")
                        .then(ClientCommandManager.argument("root", StringArgumentType.word())
                                .executes(ctx -> deny(ctx, StringArgumentType.getString(ctx, "root"))))));
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        GuardConfig config = GuardConfig.get();
        feedback(ctx, Component.literal("CmdGuard is ")
                .withStyle(ChatFormatting.GOLD)
                .append(config.enabled
                        ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
                        : Component.literal("OFF").withStyle(ChatFormatting.RED))
                .append(Component.literal("  (" + config.allowlist.size()
                        + " allowlisted, clicked commands "
                        + (config.allowClickedCommands ? "allowed" : "blocked") + ")")
                        .withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int setEnabled(CommandContext<FabricClientCommandSource> ctx, boolean value) {
        GuardConfig config = GuardConfig.get();
        config.enabled = value;
        config.save();
        return status(ctx);
    }

    private static int list(CommandContext<FabricClientCommandSource> ctx) {
        GuardConfig config = GuardConfig.get();
        if (config.allowlist.isEmpty()) {
            feedback(ctx, Component.literal("Allowlist is empty -- everything not handled locally is blocked.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        feedback(ctx, Component.literal("Allowed outbound roots: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.join(", ", config.allowlist)).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    private static int allow(CommandContext<FabricClientCommandSource> ctx, String root) {
        boolean added = GuardConfig.get().allow(root);
        feedback(ctx, Component.literal(added ? "Now allowing /" + root : "/" + root + " was already allowed")
                .withStyle(added ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return 1;
    }

    private static int deny(CommandContext<FabricClientCommandSource> ctx, String root) {
        boolean removed = GuardConfig.get().deny(root);
        feedback(ctx, Component.literal(removed ? "No longer allowing /" + root : "/" + root + " was not on the list")
                .withStyle(removed ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return 1;
    }

    private static int clear(CommandContext<FabricClientCommandSource> ctx) {
        GuardConfig config = GuardConfig.get();
        config.allowlist.clear();
        config.save();
        feedback(ctx, Component.literal("Allowlist cleared -- strict mode. Note: auth commands like /login are now blocked too.")
                .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, Component message) {
        ctx.getSource().sendFeedback(message);
    }
}
