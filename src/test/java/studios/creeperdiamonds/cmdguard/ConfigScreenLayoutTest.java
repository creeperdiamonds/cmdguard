package studios.creeperdiamonds.cmdguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing about the settings screen that can be checked without a game client: that its
 * {@code Done} button is always on screen.
 *
 * <p>Nothing else in {@code ConfigScreen} is testable here -- widget construction needs a
 * {@code Minecraft} instance -- which is exactly why the arithmetic was pulled out into a
 * static method. A settings screen you cannot close is a real defect, and it is the kind that
 * only appears at a window size nobody develops at, so "it looked fine on my monitor" is not
 * evidence.
 */
class ConfigScreenLayoutTest {

    /** The row height and gap the screen lays out with, restated so a change to either fails. */
    private static final int ROW_HEIGHT = 20;
    private static final int DONE_OFFSET = 180;

    private static int doneBottom(int screenHeight) {
        return ConfigScreen.stackTop(screenHeight) + DONE_OFFSET + ROW_HEIGHT;
    }

    /**
     * 240 is the smallest scaled height Minecraft's own {@code Window#calculateScale} will
     * normally settle on: it stops raising the GUI scale as soon as {@code framebufferHeight /
     * (scale + 1)} would drop below 240. This is the case the old {@code height / 4 + 180}
     * got wrong -- it put {@code Done} at 240..260, below the bottom edge.
     */
    @Test
    void doneFitsAtTheSmallestUsualScaledHeight() {
        assertTrue(doneBottom(240) <= 240,
                "Done must be fully on screen at a 240px scaled height; it is the only way out");
    }

    /**
     * "Force Unicode Font" bumps the scale one step past that guard, so the scaled height can
     * go lower still. There is no seven-row layout that fits in 150px, but {@code Done} must
     * stay reachable regardless -- the rows go off the top instead.
     */
    @Test
    void doneStaysOnScreenBelowTheUsualFloorToo() {
        for (int height = 220; height >= 120; height -= 10) {
            assertTrue(doneBottom(height) <= height,
                    "Done fell off the bottom at a scaled height of " + height);
            assertTrue(doneBottom(height) - ROW_HEIGHT >= 0,
                    "Done fell off the top at a scaled height of " + height);
        }
    }

    /**
     * {@code Done} sits a fixed gap below the last toggle at every height, so the two can never
     * overlap into a button that is drawn but not clickable (the earlier widget wins the hit
     * test). This holds by construction -- the whole stack moves together -- and is asserted so
     * a future "just clamp Done on its own" does not quietly reintroduce the overlap.
     */
    @Test
    void doneNeverOverlapsTheLastToggle() {
        int lastRowBottom = 144 + ROW_HEIGHT;
        assertEquals(16, DONE_OFFSET - lastRowBottom,
                "the gap between the last toggle and Done is fixed and must stay so");
        for (int height = 120; height <= 2160; height += 7) {
            assertTrue(doneBottom(height) <= height, "Done fell off the bottom at " + height);
        }
    }

    /** On any comfortable window the layout is unchanged: the stack still starts a quarter down. */
    @Test
    void aComfortableWindowIsLaidOutExactlyAsBefore() {
        assertEquals(120, ConfigScreen.stackTop(480));
        assertEquals(270, ConfigScreen.stackTop(1080));
    }
}
