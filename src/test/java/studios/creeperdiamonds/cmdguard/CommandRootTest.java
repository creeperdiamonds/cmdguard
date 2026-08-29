package studios.creeperdiamonds.cmdguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared root parser. {@code OutboundGuard#rootOf} delegates here, so these cases pin the
 * behaviour of the typed-command guard as well as the suggestion guard -- which is the point
 * of there being one copy of it.
 */
class CommandRootTest {

    @Test
    void stripsTheLeadingSlash() {
        assertEquals("msg", CommandRoot.of("/msg"));
    }

    @Test
    void acceptsTextWithNoLeadingSlash() {
        // A command block's edit box has no slash, and its completion requests ride the very
        // same packet. Vanilla's own handleCustomCommandSuggestions strips the slash only if
        // it is there.
        assertEquals("setblock", CommandRoot.of("setblock ~ ~ ~ stone"));
    }

    @Test
    void takesOnlyTheFirstWord() {
        assertEquals("msg", CommandRoot.of("/msg Steve hello there"));
    }

    @Test
    void lowercases() {
        assertEquals("msg", CommandRoot.of("/MsG Steve"));
    }

    @Test
    void yieldsAnEmptyRootWhenThereIsNoWordYet() {
        assertEquals("", CommandRoot.of(""));
        assertEquals("", CommandRoot.of("/"));
        assertEquals("", CommandRoot.of("/ msg"));
    }

    @Test
    void isNullSafe() {
        assertEquals("", CommandRoot.of(null));
    }

    @Test
    void endsTheRootAtAnyWhitespace() {
        // Not just ' '. Defensive only -- Tab is the completion key, so a tab cannot be typed
        // into a chat or command-block box, and brigadier refuses a literal followed by
        // anything but a space regardless. But this parser now answers for both guards, so
        // "first word" means one thing rather than "first space-delimited run".
        assertEquals("msg", CommandRoot.of("/msg\tSteve"));
        assertEquals("msg", CommandRoot.of("/msg\nSteve"));
        assertEquals("msg", CommandRoot.of("msg\r\nSteve"));
        assertEquals("", CommandRoot.of("/\tmsg"));
    }

    @Test
    void keepsAColonNamespaceInTheRoot() {
        // "/somemod:debug" is one root, not a namespace and a command: this is exactly the
        // shape the guard exists to keep off the wire.
        assertEquals("somemod:debug", CommandRoot.of("/somemod:debug arg"));
    }
}
