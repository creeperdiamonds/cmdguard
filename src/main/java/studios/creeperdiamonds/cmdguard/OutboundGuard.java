package studios.creeperdiamonds.cmdguard;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

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
     * A root shorter than this can still be recorded (so an exact repeat of it dedupes), but
     * it can never suppress a <em>different</em> root, longer or shorter. See
     * {@link #recordSuggestionRoot} for what this bounds and why two is the chosen width.
     */
    private static final int MIN_PLAUSIBLE_ROOT_LENGTH = 2;

    /**
     * Roots whose withheld completion requests have already been reported. Not pairwise
     * prefix-incomparable any more: a single-character entry may sit alongside a longer entry
     * that starts with it, because only an entry at least {@link #MIN_PLAUSIBLE_ROOT_LENGTH}
     * long is trusted to speak for a whole family. See {@link #recordSuggestionRoot} for the
     * exact rule and {@link #logFirstSuggestionBlock} for why that is the bound.
     *
     * <p>Guarded by its own monitor rather than being a concurrent set, because the check is
     * now check-then-act over the <em>whole</em> set -- {@link #shouldBlockSuggestion} is
     * reached from {@code Connection#sendPacket}, which provably runs on both the client
     * thread and the netty event loop, and an unsynchronised scan-then-add there would let two
     * threads both decide to log the same root. Every read and every write of this set and of
     * {@link #reportedRootlessRequest} happens inside {@code synchronized (
     * REPORTED_SUGGESTION_ROOTS)}; the log call itself is deliberately outside it, so nothing
     * holds a lock across an appender.
     *
     * <p>The empty root is never put in here. It is a prefix of every string, so one rootless
     * request would suppress every later line; {@link #reportedRootlessRequest} tracks it
     * separately instead.
     */
    private static final Set<String> REPORTED_SUGGESTION_ROOTS = new HashSet<>();

    /** Whether the one line about a rootless request has been emitted. Guarded as above. */
    private static boolean reportedRootlessRequest;

    private OutboundGuard() {
    }

    /**
     * The typed-command half, for {@code ClientPacketListenerMixin}.
     *
     * <p>The decision itself is {@link CommandFilter#blocks}, which is Minecraft-free and
     * unit-tested; this only supplies the live config and the client-dispatcher lookup. Same
     * split as {@link #shouldBlockSuggestion} / {@link SuggestionFilter}, and it is what lets
     * {@code SuggestionFilterTest} compare the two guards against each other rather than
     * against a copy of one of them.
     */
    public static boolean shouldBlock(String command, boolean clicked) {
        GuardConfig config = GuardConfig.get();
        return CommandFilter.blocks(command, clicked, config.enabled,
                config.allowClickedCommands, config.allowlist, OutboundGuard::isClientCommand);
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
     * One INFO line the first time completions are withheld for a root, where "a root" is
     * taken up to prefixes: a root is reported only when no already-reported root <em>at
     * least {@link #MIN_PLAUSIBLE_ROOT_LENGTH} characters long</em> is a prefix of it and it
     * is a prefix of no such already-reported root. A root shorter than that never suppresses
     * anything but an exact repeat of itself. See {@link #recordSuggestionRoot} for the exact
     * rule.
     *
     * <p><b>Why prefixes, and not simply once per distinct root.</b> The plain per-root dedupe
     * this replaced was justified on the grounds that "a request goes out per keystroke and
     * the same root repeats for every character of its arguments" -- which is true only while
     * the root is stable, i.e. in the ordinary case where the server hangs its {@code
     * ASK_SERVER} node on an <em>argument</em>. The case this guard exists for is the other
     * one: a server that hangs {@code ASK_SERVER} directly under the root harvests keystrokes,
     * and there the root grows by one character per keystroke. Typing {@code /somemod:debug}
     * then produced fourteen INFO lines -- {@code s}, {@code so}, {@code som}, {@code some},
     * ... -- each carrying the full explanatory sentence, reconstructing the typed command
     * name character by character in {@code latest.log}. Nothing reached the server and no
     * argument was ever logged, but the stated bound was not the real bound, and a guard
     * writing the very keystrokes it withheld into a file is not a detail to leave standing.
     *
     * <p><b>The real suppression width, stated plainly, because the first version of this
     * rule understated its own cost.</b> A single character (the first key of the harvest
     * case above) is not enough evidence that a family exists: {@code s} is a prefix of
     * essentially every root beginning with that letter, so letting it suppress meant every
     * later, wholly unrelated command sharing only that first letter -- {@code setblock},
     * {@code spawn}, {@code somemod:debug} were the very examples this guard exists for --
     * was withheld with no log line at all, for the rest of the process, across reconnects
     * and across servers. "Tab does nothing, and the log says nothing" is exactly the
     * silent-filter condition this INFO line exists to prevent. The fix: an entry only gets to
     * suppress a <em>different</em> root, longer or shorter, once it is itself at least {@link
     * #MIN_PLAUSIBLE_ROOT_LENGTH} characters. A root shorter than that is still recorded (so
     * an exact repeat of it, e.g. a standalone one-letter command retyped, is still deduped)
     * but is never trusted to speak for anything beyond itself -- every longer root that
     * merely happens to share its first character is now reported in its own right, the moment
     * it reaches the threshold. Two was chosen, not one, because it is the smallest length at
     * which a shared prefix has stopped meaning "the same first keystroke of any command in
     * the alphabet" and started meaning "these two requests probably continue the same typed
     * word" -- the same order of coincidence this rule already accepts for {@code ban}/{@code
     * banip} below, not the whole-alphabet collision a bare first character produces. The
     * character-by-character example above still produces at most two lines under this rule
     * -- one for the bare {@code s} the first time any family reaches it, and one for {@code
     * so}, the first entry at or past the threshold -- not fourteen, and not zero.
     *
     * <p>Prefix dedupe was chosen over the other candidate -- keep the first line at INFO and
     * drop later ones to DEBUG -- because it bounds the log <em>structurally</em>, at every
     * log level. DEBUG lines are merely hidden by the default log4j configuration; a user
     * running with debug logging on, which is exactly what someone diagnosing a mod does,
     * would get the whole character-by-character reconstruction back. This instead never
     * writes the later lines at all, and it keeps a per-command INFO line in the ordinary
     * case, where the first root seen is already the complete one ({@code /somemod:debug
     * <Tab>} on an argument reports {@code somemod:debug}, exactly as before).
     *
     * <p>The first occurrence is still INFO, and that is the point: a silent filter and a
     * filter that never ran look identical from the outside, which is how the inbound
     * filter's mixin-ordering defect survived. Here the only user-visible symptom is "tab does
     * nothing", far too quiet to diagnose on its own. The cost of the prefix rule is that a
     * genuinely distinct root which happens to extend a reported one that has already reached
     * the threshold ({@code banip} after {@code ban}) is not reported separately; one visible
     * line per session per command <em>family</em> is enough to answer "did the guard fire",
     * and the config screen's toggle is where the rest of the answer is.
     *
     * <p>Nothing is ever cleared. The set stays bounded: per family, at most one entry shorter
     * than {@link #MIN_PLAUSIBLE_ROOT_LENGTH} (there is only one possible first character for
     * any one typed word) plus one prefix-incomparable entry at or past it, and a repeat line
     * after a reconnect would add nothing the first one did not already say.
     */
    private static void logFirstSuggestionBlock(String root) {
        if (root.isEmpty()) {
            synchronized (REPORTED_SUGGESTION_ROOTS) {
                if (reportedRootlessRequest) {
                    return;
                }
                reportedRootlessRequest = true;
            }
            CmdGuardClient.LOGGER.info(
                    "[cmdguard] withheld a tab-completion request with no command root yet"
                            + " (\"/\" on its own, or an empty command box). Repeats are not"
                            + " logged. Command names are completed from the client's own copy"
                            + " of the command tree, so this costs you nothing.");
            return;
        }
        if (!recordSuggestionRoot(root)) {
            return;
        }
        if (root.length() < MIN_PLAUSIBLE_ROOT_LENGTH) {
            CmdGuardClient.LOGGER.info(
                    "[cmdguard] withheld the tab-completion request for \"{}\" -- that root is"
                            + " not on your allowlist, and a completion request puts the partial"
                            + " command on the wire just as running it would. This is only the"
                            + " first character typed so far; a longer command will still get"
                            + " its own line once it is a couple of characters in. Run"
                            + " \"/cmdguard allow <root>\" with the whole command root to allow"
                            + " it and its completions, or switch the suggestion guard off in the"
                            + " config screen.",
                    root);
            return;
        }
        CmdGuardClient.LOGGER.info(
                "[cmdguard] withheld the tab-completion request for \"{}\" -- that root is not on"
                        + " your allowlist, and a completion request puts the partial command on"
                        + " the wire just as running it would. Neither this root nor any longer"
                        + " one starting with it is logged again, so if you were still mid-word"
                        + " this names only what you had typed at the time. Run \"/cmdguard allow"
                        + " <root>\" with the whole command root to allow it and its completions,"
                        + " or switch the suggestion guard off in the config screen.",
                root);
    }

    /**
     * Adds {@code root} to {@link #REPORTED_SUGGESTION_ROOTS} and returns true, unless it is
     * suppressed by something already there -- in which case nothing is added and it returns
     * false.
     *
     * <p>An exact repeat of an already-reported root is always suppressed, whatever its
     * length -- that is simple deduplication, not a family judgement. Beyond that, a reported
     * root only suppresses a <em>different</em> root, via the prefix check in either
     * direction, once that reported root is at least {@link #MIN_PLAUSIBLE_ROOT_LENGTH}
     * characters long. A reported root that is a prefix of this one and long enough is the
     * keystroke-by-keystroke case ({@code so} then {@code some}); this one being a prefix of
     * such a reported root is the same typing done in the other order, after a backspace or a
     * retype. A reported root shorter than the threshold (necessarily a single character,
     * since the empty root never reaches here) is kept only so a later exact repeat of that
     * same single character is still deduped -- it is never used to suppress a longer or
     * shorter root that merely happens to share it, which is the defect this threshold exists
     * to fix. See {@link #logFirstSuggestionBlock} for the scenario and the width chosen.
     *
     * <p>Package-private so {@code SuggestionLogDedupeTest} can drive the dedupe
     * without a game client. {@link #resetSuggestionLogForTest()} is there for the same
     * reason; nothing in the mod calls either.
     */
    static boolean recordSuggestionRoot(String root) {
        synchronized (REPORTED_SUGGESTION_ROOTS) {
            for (String reported : REPORTED_SUGGESTION_ROOTS) {
                if (reported.equals(root)) {
                    return false;
                }
                if (reported.length() >= MIN_PLAUSIBLE_ROOT_LENGTH
                        && (root.startsWith(reported) || reported.startsWith(root))) {
                    return false;
                }
            }
            return REPORTED_SUGGESTION_ROOTS.add(root);
        }
    }

    /** Clears the reported-root state. Tests only; the mod never forgets within a session. */
    static void resetSuggestionLogForTest() {
        synchronized (REPORTED_SUGGESTION_ROOTS) {
            REPORTED_SUGGESTION_ROOTS.clear();
            reportedRootlessRequest = false;
        }
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
