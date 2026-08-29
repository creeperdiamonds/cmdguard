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
                        // ':', so word() cannot accept "somemod:handshake" at all. Greedy is
                        // NOT free, though -- it swallows the rest of the line, spaces and
                        // all, so "my mod:hand shake" arrives at the handler as one argument.
                        // ExposurePolicy.isWellFormedChannelId is what rejects it; see
                        // normaliseChannel below and that method's Javadoc.
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

        // The exposure layer is gated on config.enabled too, so /cmdguard off turns it off
        // as well. Reporting exposure.enabled on its own here would tell the player the
        // whitelist is on while nothing at all is being withheld.
        feedback(ctx, Component.literal("Exposure whitelist is ")
                .withStyle(ChatFormatting.GOLD)
                .append(config.exposureActive()
                        ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
                        : Component.literal("OFF").withStyle(ChatFormatting.RED))
                .append(Component.literal("  (" + exposureQualifier(config) + ")")
                        .withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    /** Why filtering is off for the connection this snapshot belongs to. */
    private static String exposureOffReason(ExposureGuard.Snapshot snapshot) {
        if (ExposureGuard.SINGLEPLAYER_KEY.equals(snapshot.serverKey())) {
            return ": singleplayer is exempt, because your client is talking to its own "
                    + "integrated server in this same process and there is nobody to withhold from.";
        }
        if (GuardConfig.get().exposure.enabled) {
            return ", because /cmdguard off disables the exposure layer too.";
        }
        return ".";
    }

    /** The parenthetical that says <em>why</em> exposure filtering is in the state it is. */
    private static String exposureQualifier(GuardConfig config) {
        if (!config.exposure.enabled) {
            return "switched off in the config screen";
        }
        if (!config.enabled) {
            return "the exposure toggle is on, but /cmdguard off disables it too -- "
                    + "run /cmdguard on to restore it";
        }
        return "inbound probes "
                + (config.exposure.filterInbound ? "blocked" : "allowed")
                + ", login queries "
                + (config.exposure.loginFilterEnabled() ? "answered by vanilla" : "answerable by mods")
                + ", " + config.exposure.exposedNamespaces.size() + " namespaces exposed";
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
        ExposureGuard.Snapshot connectionSnapshot = ExposureGuard.currentSnapshot();

        // Said first, and unconditionally: an empty ledger because filtering is off reads
        // exactly like an empty ledger because nothing happened, and the difference is the
        // whole point of the readout. The connection's own frozen snapshot is authoritative
        // here, not the live config -- a mid-session toggle does not change this connection.
        if (connectionSnapshot != null && !connectionSnapshot.active()) {
            feedback(ctx, Component.literal("Exposure filtering is ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("OFF").withStyle(ChatFormatting.RED))
                    .append(Component.literal(" for this connection -- nothing below was withheld"
                                    + exposureOffReason(connectionSnapshot))
                            .withStyle(ChatFormatting.GRAY)));
        }

        if (entries.isEmpty()) {
            feedback(ctx, Component.literal("No channels observed yet on this connection.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }

        long exposed = entries.stream().filter(ChannelLedger.Entry::exposed).count();
        // As in expose(): a null key on a live connection means a transfer and nothing else.
        String server = connectionSnapshot == null || connectionSnapshot.serverKey() == null
                ? "a transferred connection (global grants only)"
                : connectionSnapshot.serverKey();

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

        // A null server key now means exactly one thing. Singleplayer gets the real reserved
        // "singleplayer" key, and the pre-handshake window is not reachable by a typed
        // command, so the only connection that reaches this branch is a transferred one.
        if (!global && server == null) {
            feedback(ctx, Component.literal(
                            "This connection was reached by a server transfer, so it gets global "
                                    + "grants only and has no per-server identity. Use /cmdguard expose global "
                                    + value + " to allow it everywhere.")
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

    /**
     * {@code /cmdguard withhold <namespace>} -- the inverse of {@code expose}, but
     * deliberately not its mirror image: {@code expose} needs an explicit {@code global}
     * literal to touch the global set, while this drops the grant in <em>every</em> scope at
     * once. Widening asks for the wider word; narrowing does not, because a "withhold" that
     * quietly left a global grant standing would report success and withhold nothing, which
     * is the one direction this feature must not fail in.
     *
     * <p>What it does not do is stay silent about it. The scopes it actually changed are
     * named in the output, so "withholding X" is never a claim the player has to take on
     * trust.
     */
    private static int withhold(CommandContext<FabricClientCommandSource> ctx, String namespace) {
        GuardConfig config = GuardConfig.get();
        String value = namespace.toLowerCase(Locale.ROOT);

        boolean removedGlobal = config.exposure.exposedNamespaces.remove(value);
        ExposureGuard.Snapshot snapshot = ExposureGuard.currentSnapshot();
        String server = snapshot == null ? null : snapshot.serverKey();
        Set<String> perServer = server == null
                ? null
                : config.exposure.perServerNamespaces.get(server);
        boolean removedPerServer = perServer != null && perServer.remove(value);
        config.save();

        if (!removedGlobal && !removedPerServer) {
            feedback(ctx, Component.literal(value + " was not exposed -- nothing changed.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }

        feedback(ctx, Component.literal("Withholding " + value + " -- takes effect on your next connection.")
                .withStyle(ChatFormatting.YELLOW));
        if (removedGlobal) {
            feedback(ctx, Component.literal("  removed the global grant (it applied on every server)")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (removedPerServer) {
            feedback(ctx, Component.literal("  removed the grant for " + server)
                    .withStyle(ChatFormatting.GRAY));
        }
        warnIfDefaultNamespace(ctx, value);
        return 1;
    }

    /**
     * The three default namespaces are not ordinary grants. {@code fabric}, {@code minecraft}
     * and {@code c} are the generic namespaces every Fabric client has -- which is exactly
     * why they distinguish nobody and are exposed by default. Withholding one breaks joining
     * a modded server outright: this branch's own {@code ExposureGuard.globalsOnlySnapshot}
     * javadoc makes the same argument for why the fallback is not deny-all, because a
     * withheld {@code minecraft:register} stops the server's registry sync.
     */
    private static void warnIfDefaultNamespace(CommandContext<FabricClientCommandSource> ctx, String value) {
        if (!ExposurePolicy.DEFAULT_NAMESPACES.contains(value)) {
            return;
        }
        feedback(ctx, Component.literal("Warning: " + value + " is one of the three generic "
                        + "namespaces every Fabric client has, so withholding it hides nothing about "
                        + "you and will break joining modded servers -- minecraft:register and the "
                        + "Fabric registry sync ride on them. Undo with /cmdguard expose global " + value + ".")
                .withStyle(ChatFormatting.RED));
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
     *
     * <p>{@code trim()} strips the ends only, on purpose. An id with a space in the middle
     * -- which the {@code greedyString} argument happily delivers -- is a typo, not
     * something to silently repair: squeezing the space out would store an id the user did
     * not type and did not check. It is refused instead, by {@link
     * ExposurePolicy#isWellFormedChannelId}, whose charset test is what makes that
     * refusal happen.
     */
    private static String normaliseChannel(CommandContext<FabricClientCommandSource> ctx, String channel) {
        String value = channel.trim().toLowerCase(Locale.ROOT);
        if (!ExposurePolicy.isWellFormedChannelId(value)) {
            feedback(ctx, Component.literal(
                            "\"" + channel + "\" is not a channel id, so nothing was stored. "
                                    + "Channels look like namespace:path, e.g. somemod:handshake -- "
                                    + "one colon, no spaces, and only a-z 0-9 _ - . in the namespace "
                                    + "(the path may also contain /). Run /cmdguard exposure or "
                                    + "/cmdguard audit to see real ones. For a whole namespace, use "
                                    + "/cmdguard withhold <namespace>.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return value;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, Component message) {
        ctx.getSource().sendFeedback(message);
    }
}
