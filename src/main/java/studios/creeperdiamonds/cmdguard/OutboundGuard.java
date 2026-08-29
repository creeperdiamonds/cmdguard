package studios.creeperdiamonds.cmdguard;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decision: does this command leave the machine?
 *
 * <p>Anything reaching ClientPacketListener#sendCommand has already been offered to
 * Fabric's client dispatcher, so a command handled locally normally never gets here.
 * We re-check the dispatcher anyway via the public getActiveDispatcher() so the result
 * does not depend on mixin injection order.
 */
public final class OutboundGuard {

    /**
     * Roots whose first withheld completion request has already been logged.
     *
     * <p>Concurrent because {@link #shouldBlockSuggestion} is reached from {@code
     * Connection#sendPacket}, which provably runs on both the client thread and the netty
     * event loop.
     */
    private static final Set<String> REPORTED_SUGGESTION_ROOTS = ConcurrentHashMap.newKeySet();

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

    /**
     * The suggestion-request half of the same decision, for {@code ConnectionMixin}.
     *
     * <p>True when a {@code ServerboundCommandSuggestionPacket} carrying {@code text} must not
     * leave the client. The decision itself is {@link SuggestionFilter#withholds}, which is
     * Minecraft-free and unit-tested; this only supplies the live config and the one log line.
     *
     * <p><b>No chat message, unlike {@link #reportBlocked}.</b> A completion request is sent
     * on every keystroke, so a chat line per block would bury the chat within a word. It also
     * runs on whichever thread {@code Connection#sendPacket} is on -- which includes the netty
     * event loop -- where touching {@code Minecraft#gui} is not safe. The log line below is
     * the observability instead, and the toggle in the config screen is where a user who
     * wonders why completions stopped finds the answer.
     *
     * <p>Deliberately does not consult {@link #isClientCommand}: see {@link SuggestionFilter}'s
     * class Javadoc for why a client-owned root is exempt from the command guard but not from
     * this one. That also keeps this path from touching Fabric's client dispatcher off the
     * client thread.
     */
    public static boolean shouldBlockSuggestion(String text) {
        GuardConfig config = GuardConfig.get();
        boolean withheld = SuggestionFilter.withholds(
                text, config.enabled, config.guardSuggestions, config.allowlist);
        if (withheld) {
            logFirstSuggestionBlock(CommandRoot.of(text));
        }
        return withheld;
    }

    /**
     * One INFO line the first time completions are withheld for a given root.
     *
     * <p>Once per root rather than once per request, because a request goes out per keystroke
     * and the same root repeats for every character of its arguments. The set is never
     * cleared: it bounds the log to the distinct roots typed in a session, which is what makes
     * it safe to leave at INFO, and a repeat line after a reconnect would add nothing the
     * first one did not already say.
     *
     * <p>It exists for the same reason the exposure layer's withhold lines do -- a silent
     * filter and a filter that never ran look identical from the outside, and that is exactly
     * how the inbound filter's mixin-ordering defect survived. Here the user-visible symptom
     * is only "tab does nothing", which is far too quiet to diagnose on its own.
     */
    private static void logFirstSuggestionBlock(String root) {
        if (!REPORTED_SUGGESTION_ROOTS.add(root)) {
            return;
        }
        if (root.isEmpty()) {
            CmdGuardClient.LOGGER.info(
                    "[cmdguard] withheld a tab-completion request with no command root yet"
                            + " (\"/\" on its own, or an empty command box). Repeats are not"
                            + " logged. Command names are completed from the client's own copy"
                            + " of the command tree, so this costs you nothing.");
            return;
        }
        CmdGuardClient.LOGGER.info(
                "[cmdguard] withheld the tab-completion request for \"{}\" -- that root is not on"
                        + " your allowlist, and a completion request puts the partial command on"
                        + " the wire just as running it would. Repeats are not logged. Run"
                        + " \"/cmdguard allow {}\" to allow both, or switch the suggestion guard"
                        + " off in the config screen.",
                root, root);
    }

    public static String rootOf(String command) {
        return CommandRoot.of(command);
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
