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
     */
    public static String of(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        int space = trimmed.indexOf(' ');
        String root = space < 0 ? trimmed : trimmed.substring(0, space);
        return root.toLowerCase(Locale.ROOT);
    }
}
