package studios.creeperdiamonds.cmdguard;

import java.util.Set;

/**
 * The whole tab-completion decision, as one pure function over a string and the command
 * policy.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested on a machine with no game
 * client -- which is every machine this project has ever been built on. The Minecraft-facing
 * half (reading the text off the packet, logging, cancelling the send) lives in
 * {@code OutboundGuard#shouldBlockSuggestion} and {@code ConnectionMixin}; everything that
 * decides lives here. Same split as {@code LoginQueryFilter} / {@code ExposureGuard}.
 *
 * <p><b>Why this exists.</b> The command guard blocks a command whose root is not on the
 * allowlist, so {@code /somemod:debug} never leaves the client. Tab completion leaks the same
 * text one step earlier: {@code ClientSuggestionProvider#customSuggestion} sends
 * {@code new ServerboundCommandSuggestionPacket(id, commandContext.getInput())} -- the partial
 * command, as typed -- through {@code ClientPacketListener#send}, i.e. through
 * {@code Connection#sendPacket}, the same outbound choke point the exposure layer uses. A
 * guard that blocks {@code /foo} while happily sending {@code foo} as a completion request is
 * not much of a guard.
 *
 * <p><b>The same allowlist, on purpose.</b> There is no second policy and no second list here:
 * a suggestion request is judged by exactly the rule that governs the command it would become.
 * That is the only reading under which the two guards cannot disagree.
 *
 * <p><b>Two deliberate divergences from {@link CommandFilter#blocks}</b>, both in the
 * strict direction, both because a suggestion request <em>is</em> traffic where a blocked
 * command is not:
 *
 * <ul>
 *   <li><b>An empty root is withheld, not allowed.</b> {@code CommandFilter} lets an empty
 *       root through because an empty command runs nothing and sends nothing. Here the text
 *       goes on the wire regardless of whether it parses, so it must be judged -- and an
 *       empty root is by definition not on the allowlist. Nothing is lost: the completion of
 *       a command <em>name</em> is served locally from the client's own command tree (see
 *       the class note on partial roots below).</li>
 *   <li><b>A client-command root is <em>not</em> exempt.</b> {@code CommandFilter} exempts a
 *       root owned by the client dispatcher because such a command never reaches the network.
 *       A suggestion request for it does. {@code NOTES.md}'s leak vector #3 is precisely this:
 *       a client mod that hangs {@code SuggestionProviders.ASK_SERVER} on one of its own
 *       arguments ships a command that looks local and tab-completes straight to the server,
 *       disclosing the mod's command name. Exempting client roots here would wave through the
 *       one case this guard is best placed to catch, so it does not.</li>
 * </ul>
 *
 * <p><b>A partial command may not yet have a complete root, and that is the cost.</b> Typing
 * {@code /ms} has root {@code ms}, which is not on the allowlist even though the user is on
 * their way to {@code /msg}. This withholds it. The conservative reading is the correct one --
 * the alternative is a prefix rule, which would send {@code /m}, {@code /ms} and every other
 * prefix of an allowlisted root to the server, i.e. would leak the very keystrokes it claims
 * to guard. So the command <em>name</em> cannot be completed against the server, only its
 * arguments. The README says so plainly rather than leaving a user to discover it.
 *
 * <p>In practice that cost is much smaller than it sounds, and the reason is worth recording:
 * the client completes command names locally. {@code CommandSuggestions#updateCommandInfo}
 * parses against {@code ClientPacketListener#getCommands()} -- the tree the server already
 * sent in {@code ClientboundCommandsPacket} -- and a root-level child is a
 * {@code LiteralCommandNode}, whose {@code listSuggestions} matches literals in memory and
 * sends nothing. A {@code ServerboundCommandSuggestionPacket} is only produced when an
 * <em>argument</em> node's suggestion provider asks the server. So {@code /ms<Tab>} normally
 * sends nothing at all and this filter changes nothing about it. The policy is still stated
 * and enforced conservatively, because the command tree is server-supplied: a server that
 * wanted the client's keystrokes could put an {@code ASK_SERVER} argument node directly under
 * the root and get every character typed after the slash. That is the case this closes.
 */
public final class SuggestionFilter {

    private SuggestionFilter() {
    }

    /**
     * True when a completion request for {@code text} must not leave the client.
     *
     * <p>Fails closed on everything it cannot decide: a null allowlist is withheld, and any
     * {@code RuntimeException} out of the set is withheld. The two switches are checked
     * first and are the only way to get a {@code false} out of this method without a policy
     * decision, so turning the feature off is never confused with failing to decide.
     *
     * @param text             the packet's command text, exactly as
     *                         {@code ServerboundCommandSuggestionPacket#getCommand} carries
     *                         it: the typed line truncated at the cursor, with a leading
     *                         {@code '/'} when the player is in chat and without one when
     *                         they are in a command block's edit box.
     * @param guardEnabled     the guard's master switch ({@code GuardConfig#enabled}).
     * @param guardSuggestions whether the tab-completion guard specifically is on
     *                         ({@code GuardConfig#guardSuggestions}).
     * @param allowlist        the command allowlist, already lowercased by
     *                         {@code GuardConfig#allow}. Read here without a lock, from
     *                         whichever thread {@code Connection#sendPacket} runs on; a
     *                         concurrent {@code /cmdguard allow} can therefore make this read
     *                         answer for either side of the edit, and if it throws, the
     *                         completion is withheld. Both outcomes are acceptable for one
     *                         keystroke's suggestions and neither can leak.
     */
    public static boolean withholds(String text,
                                    boolean guardEnabled,
                                    boolean guardSuggestions,
                                    Set<String> allowlist) {
        if (!guardEnabled || !guardSuggestions) {
            return false;
        }
        if (allowlist == null) {
            return true;
        }
        try {
            String root = CommandRoot.of(text);
            if (root.isEmpty()) {
                return true;
            }
            return !allowlist.contains(root);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
