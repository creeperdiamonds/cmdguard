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
    void keepsAColonNamespaceInTheRoot() {
        // "/somemod:debug" is one root, not a namespace and a command: this is exactly the
        // shape the guard exists to keep off the wire.
        assertEquals("somemod:debug", CommandRoot.of("/somemod:debug arg"));
    }
}
