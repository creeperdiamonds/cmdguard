package studios.creeperdiamonds.cmdguard;

import java.util.Locale;

/**
 * The one definition of "the root of a command", shared by the two guards that need it.
 *
 * <p>Extracted from {@code OutboundGuard} when the tab-completion guard arrived, so that both
 * the typed-command path and the suggestion path judge the same string. A second copy of this
 * four-line parser would be a second, silently divergent notion of what {@code /msg} means --
 * and the whole point of the suggestion guard is that a suggestion request is judged by the
 * same rule as the command it would become.
 *
 * <p>Deliberately free of Minecraft types: {@code OutboundGuard} cannot be unit-tested on a
 * machine with no game client, and this can.
 *
 * <p>The leading slash is optional, and that is not laxity. A typed chat command carries one;
 * the text in {@code AbstractCommandBlockEditScreen}'s edit box does not, and it reaches the
 * wire through the very same {@code ServerboundCommandSuggestionPacket}. Vanilla's own
 * {@code ServerGamePacketListenerImpl#handleCustomCommandSuggestions} strips an optional
 * leading {@code '/'} from that packet's text for exactly that reason; this matches it.
 */
public final class CommandRoot {

    private CommandRoot() {
    }

    /**
     * The lowercased first word of {@code command}, with an optional leading {@code '/'}
     * stripped, or the empty string when there is no first word.
     *
     * <p>Null-safe: a null input yields {@code ""}, which every caller already treats as
     * "no root here". Nothing in the client passes null today; the guard is here because the
     * suggestion path reads its string off a packet, and a packet is a wider door than a
     * method parameter.
     *
     * <p><b>"Word" ends at any whitespace, not only at {@code ' '}.</b> This used to split on
     * the space character alone, so {@code "msg\tSteve"} had the root {@code "msg\tsteve"} --
     * one string that is plainly not a command root by any reading. Now that the same parser
     * answers for both guards, one notion of "first word" is worth more than the old one's
     * accidental strictness.
     *
     * <p>Recorded because it is a direction change and should not be rediscovered as a
     * surprise: the old root {@code "msg\tsteve"} was on nobody's allowlist, so such input was
     * blocked (typed command) and withheld (completion); the new root {@code "msg"} can be
     * allowlisted, so it may now be let through. That is defensive-only ground in both
     * directions. Whitespace inside a command cannot be produced by the paths that reach here
     * -- Tab is the completion key, so a tab cannot be typed into a chat or command-block box
     * -- and vanilla's own parser refuses it anyway: brigadier's {@code
     * CommandDispatcher#parseNodes} reads the literal with {@code readUnquotedString} and then
     * errors unless the next character is a space, so {@code /msg\tSteve} never executes on
     * the server whichever root this returns.
     */
    public static String of(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        return trimmed.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
