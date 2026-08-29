package studios.creeperdiamonds.cmdguard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import studios.creeperdiamonds.cmdguard.exposure.ChannelLedger;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;
import studios.creeperdiamonds.cmdguard.exposure.ExposurePolicy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
                .then(ClientCommandManager.literal("exposure").executes(CmdGuardCommands::exposure))
                .then(ClientCommandManager.literal("expose")
                        .then(ClientCommandManager.literal("global")
                                .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                                        .executes(ctx -> expose(ctx,
                                                StringArgumentType.getString(ctx, "namespace"), true))))
                        // greedyString, not word: Brigadier's unquoted-word charset excludes
                        // ':', so word() cannot accept "somemod:handshake" at all. Channel
                        // ids never contain a space, so greedy costs nothing here.
                        .then(ClientCommandManager.literal("channel")
                                .then(ClientCommandManager.argument("channel", StringArgumentType.greedyString())
                                        .executes(ctx -> exposeChannel(ctx,
                                                StringArgumentType.getString(ctx, "channel")))))
                        .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> expose(ctx,
                                        StringArgumentType.getString(ctx, "namespace"), false))))
                .then(ClientCommandManager.literal("withhold")
                        .then(ClientCommandManager.literal("channel")
                                .then(ClientCommandManager.argument("channel", StringArgumentType.greedyString())
                                        .executes(ctx -> withholdChannel(ctx,
                                                StringArgumentType.getString(ctx, "channel")))))
                        .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> withhold(ctx,
                                        StringArgumentType.getString(ctx, "namespace")))))
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

    private static int exposure(CommandContext<FabricClientCommandSource> ctx) {
        List<ChannelLedger.Entry> entries = ExposureGuard.ledger().snapshot();

        if (entries.isEmpty()) {
            feedback(ctx, Component.literal("No channels observed yet on this connection.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }

        long exposed = entries.stream().filter(ChannelLedger.Entry::exposed).count();
        ExposureGuard.Snapshot snapshot = ExposureGuard.currentSnapshot();
        String server = snapshot == null || snapshot.serverKey() == null
                ? "no per-server identity"
                : snapshot.serverKey();

        feedback(ctx, Component.literal("CmdGuard exposure on " + server + ": ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(exposed + " exposed").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(", " + (entries.size() - exposed) + " withheld")
                        .withStyle(ChatFormatting.YELLOW)));

        for (ChannelLedger.Entry entry : entries) {
            MutableComponent line = Component.literal("  " + entry.channel() + " ")
                    .withStyle(ChatFormatting.WHITE)
                    .append(entry.exposed()
                            ? Component.literal("EXPOSED").withStyle(ChatFormatting.GREEN)
                            : Component.literal("WITHHELD").withStyle(ChatFormatting.GOLD));

            if (entry.withheldCount() > 0) {
                line.append(Component.literal(" x" + entry.withheldCount())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (ExposurePolicy.NEVER_WITHHELD.contains(entry.channel())) {
                line.append(Component.literal("  always exposed").withStyle(ChatFormatting.DARK_GRAY));
            }
            if ("fabric:registry/sync/complete".equals(entry.channel())) {
                line.append(Component.literal(" -- required to finish joining, carries no data")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            feedback(ctx, line);
        }
        return 1;
    }

    private static int expose(CommandContext<FabricClientCommandSource> ctx,
                              String namespace, boolean global) {
        GuardConfig config = GuardConfig.get();
        String value = namespace.toLowerCase(Locale.ROOT);
        ExposureGuard.Snapshot snapshot = ExposureGuard.currentSnapshot();
        String server = snapshot == null ? null : snapshot.serverKey();

        if (!global && server == null) {
            feedback(ctx, Component.literal(
                            "No per-server identity for this connection (singleplayer, or a transfer). "
                                    + "Use /cmdguard expose global " + value + " to allow it everywhere.")
                    .withStyle(ChatFormatting.YELLOW));
            return 1;
        }

        boolean added;
        if (global) {
            added = config.exposure.exposedNamespaces.add(value);
        } else {
            added = config.exposure.perServerNamespaces
                    .computeIfAbsent(server, key -> new LinkedHashSet<>())
                    .add(value);
        }
        config.save();

        feedback(ctx, Component.literal(added
                        ? "Exposing " + value + (global ? " everywhere" : " on " + server)
                        : value + " was already exposed")
                .withStyle(added ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        feedback(ctx, Component.literal("Takes effect on your next connection.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int withhold(CommandContext<FabricClientCommandSource> ctx, String namespace) {
        GuardConfig config = GuardConfig.get();
        String value = namespace.toLowerCase(Locale.ROOT);

        boolean removed = config.exposure.exposedNamespaces.remove(value);
        ExposureGuard.Snapshot snapshot = ExposureGuard.currentSnapshot();
        String server = snapshot == null ? null : snapshot.serverKey();
        Set<String> perServer = server == null
                ? null
                : config.exposure.perServerNamespaces.get(server);
        if (perServer != null) {
            removed |= perServer.remove(value);
        }
        config.save();

        feedback(ctx, Component.literal(removed
                        ? "Withholding " + value + " -- takes effect on your next connection."
                        : value + " was not exposed")
                .withStyle(removed ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return 1;
    }

    /**
     * {@code /cmdguard expose channel <id>} -- the channel-level refinement the spec calls
     * for (design doc line 210), for the case where a namespace grant is too coarse.
     *
     * <p>Global, not per-server: {@code exposedChannels} and {@code withheldChannels} are
     * single global sets in {@code ExposureSettings}, with no per-server variant, and the
     * feedback says so rather than letting the user assume otherwise.
     *
     * <p>Also clears any withhold on the same channel, because {@code ExposurePolicy} gives
     * a channel withhold precedence over every grant -- leaving it in place would make this
     * command silently do nothing.
     */
    private static int exposeChannel(CommandContext<FabricClientCommandSource> ctx, String channel) {
        String value = normaliseChannel(ctx, channel);
        if (value == null) {
            return 1;
        }
        GuardConfig config = GuardConfig.get();

        boolean added = config.exposure.exposedChannels.add(value);
        boolean unwithheld = config.exposure.withheldChannels.remove(value);
        config.save();

        feedback(ctx, Component.literal(added || unwithheld
                        ? "Exposing channel " + value + " everywhere"
                        : "Channel " + value + " was already exposed")
                .withStyle(added || unwithheld ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        if (unwithheld) {
            feedback(ctx, Component.literal("  (also removed it from the withheld-channel list, "
                    + "which would otherwise have overridden this)").withStyle(ChatFormatting.GRAY));
        }
        feedback(ctx, Component.literal(
                        "Channel grants are global -- they apply on every server. "
                                + "Takes effect on your next connection.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * {@code /cmdguard withhold channel <id>} -- withholds one channel even where its
     * namespace is exposed. A channel withhold takes precedence over every grant, so this
     * also drops any explicit channel grant to keep the stored config honest about what is
     * actually in force.
     */
    private static int withholdChannel(CommandContext<FabricClientCommandSource> ctx, String channel) {
        String value = normaliseChannel(ctx, channel);
        if (value == null) {
            return 1;
        }
        GuardConfig config = GuardConfig.get();

        boolean added = config.exposure.withheldChannels.add(value);
        boolean ungranted = config.exposure.exposedChannels.remove(value);
        config.save();

        if (ExposurePolicy.NEVER_WITHHELD.contains(value)) {
            feedback(ctx, Component.literal(
                            "Note: " + value + " is never withheld -- "
                                    + (value.equals("minecraft:brand")
                                    ? "the brand string is your client's truthful identification and CmdGuard never alters it."
                                    : "it carries no mod data and blocking it would only cost you the join.")
                                    + " This entry will have no effect.")
                    .withStyle(ChatFormatting.YELLOW));
        }

        feedback(ctx, Component.literal(added || ungranted
                        ? "Withholding channel " + value + " everywhere"
                        : "Channel " + value + " was already withheld")
                .withStyle(added || ungranted ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        if (ungranted) {
            feedback(ctx, Component.literal("  (also removed its explicit channel grant)")
                    .withStyle(ChatFormatting.GRAY));
        }
        feedback(ctx, Component.literal(
                        "Channel withholds are global -- they apply on every server, and beat "
                                + "any namespace grant. Takes effect on your next connection.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * Lowercases a typed channel id, or reports why it cannot be one and returns null.
     *
     * <p>Refusing a malformed id matters most for {@code withhold}: an id that can never
     * match would be stored, look right in {@code cmdguard.json}, and withhold nothing --
     * a false sense of privacy, which is the one direction this feature must not fail in.
     */
    private static String normaliseChannel(CommandContext<FabricClientCommandSource> ctx, String channel) {
        String value = channel.trim().toLowerCase(Locale.ROOT);
        if (!ExposurePolicy.isWellFormedChannelId(value)) {
            feedback(ctx, Component.literal(
                            "\"" + channel + "\" is not a channel id. Channels look like "
                                    + "namespace:path, e.g. somemod:handshake -- run /cmdguard exposure "
                                    + "or /cmdguard audit to see real ones. "
                                    + "For a whole namespace, use /cmdguard withhold <namespace>.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return value;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, Component message) {
        ctx.getSource().sendFeedback(message);
    }
}
