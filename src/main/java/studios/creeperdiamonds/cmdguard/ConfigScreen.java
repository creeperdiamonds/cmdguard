package studios.creeperdiamonds.cmdguard;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minimal settings screen reached from Mod Menu. The command allowlist is edited via
 * /cmdguard allow|deny and the exposure whitelist's namespaces via /cmdguard
 * expose|withhold, so this only surfaces the six toggles (guard, clicked-command
 * policy, tab-completion guard, exposure whitelist, inbound-probe filtering, login-query
 * filtering) plus a channel audit button.
 *
 * <p>Every toggle rebuilds the whole screen rather than relabelling its own button, because
 * the guard's master switch also gates the exposure layer: the two exposure labels depend on
 * a setting a different button owns.
 */
public final class ConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 20;

    /** Offset of the {@code Done} row from the top of the stack: the last row plus a gap. */
    private static final int DONE_OFFSET = 180;

    /** Kept clear below {@code Done}, so it is never flush against the window edge. */
    private static final int BOTTOM_MARGIN = 8;

    private final Screen parent;

    /** Where {@link #init()} put the top row, so {@link #render} can put the title above it. */
    private int stackTopY;

    public ConfigScreen(Screen parent) {
        super(Component.literal("CmdGuard"));
        this.parent = parent;
    }

    /**
     * Where the first row goes: a quarter down the screen, but never so far down that
     * {@code Done} would fall off the bottom.
     *
     * <p>Package-private and static so {@code ConfigScreenLayoutTest} can check the arithmetic
     * for a small window without a game client. The defect this exists to prevent is not
     * cosmetic: {@code Done} is the only way to leave this screen, and the suggestion-guard
     * toggle grew the stack to seven rows, putting {@code Done} at {@code height/4 + 180} --
     * bottom edge {@code height/4 + 200}. Minecraft's own {@code Window#calculateScale} only
     * raises the GUI scale while {@code framebufferHeight / (scale + 1) >= 240}, so 240 is the
     * usual floor for the scaled height, and at 240 that put {@code Done} at 240..260: entirely
     * below the window. ("Force Unicode Font" bumps the scale one further and can go lower
     * still, which is why the clamp below has no lower bound.)
     *
     * <p>Clamped only downwards, deliberately. On any comfortable height this returns {@code
     * height / 4} and the layout is exactly what it always was. On a short window the whole
     * stack slides up together, keeping the 16px gap above {@code Done} intact, so the two can
     * never overlap and produce a {@code Done} button that is on-screen but not clickable. On
     * an absurdly short window the top rows go off the top instead -- the honest outcome for
     * seven rows that do not fit, and the one that still leaves {@code Done} reachable.
     */
    static int stackTop(int screenHeight) {
        return Math.min(screenHeight / 4,
                screenHeight - BOTTOM_MARGIN - DONE_OFFSET - ROW_HEIGHT);
    }

    @Override
    protected void init() {
        GuardConfig config = GuardConfig.get();
        int centerX = this.width / 2;
        int y = stackTop(this.height);
        this.stackTopY = y;

        // Toggling the guard also changes what the two exposure labels below must read --
        // config.enabled gates the exposure layer as well -- so this rebuilds the whole
        // screen rather than only relabelling itself.
        this.addRenderableWidget(Button.builder(
                Component.literal("Guard: " + (config.enabled ? "ON" : "OFF")),
                button -> {
                    config.enabled = !config.enabled;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Clicked commands: " + (config.allowClickedCommands ? "allowed" : "blocked")),
                button -> {
                    config.allowClickedCommands = !config.allowClickedCommands;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 24, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                suggestionLabel(config),
                button -> {
                    config.guardSuggestions = !config.guardSuggestions;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Audit mod channels (chat)"),
                button -> {
                    ChannelAudit.report();
                    this.onClose();
                }).bounds(centerX - 100, y + 72, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                exposureLabel(config),
                button -> {
                    config.exposure.enabled = !config.exposure.enabled;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 96, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                inboundLabel(config),
                button -> {
                    config.exposure.filterInbound = !config.exposure.filterInbound;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 120, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                loginLabel(config),
                button -> {
                    config.exposure.filterLogin = !config.exposure.filterLogin;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 144, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> this.onClose())
                .bounds(centerX - 100, y + DONE_OFFSET, 200, ROW_HEIGHT).build());
    }

    /**
     * The tab-completion toggle. Worded as what happens rather than as a switch name, for the
     * same reason as {@link #loginLabel}: this is the toggle with a visible everyday cost. On,
     * a command name cannot be completed against the server and neither can the arguments of
     * any command that is not on the allowlist -- pressing Tab simply shows nothing. That is
     * the correct trade (a completion request puts the partial command on the wire exactly as
     * running it would), but a user who meets it without being told concludes their chat is
     * broken.
     *
     * <p>Gated on {@code config.enabled} like the exposure labels, since the guard's master
     * switch turns this off too.
     */
    private static Component suggestionLabel(GuardConfig config) {
        String state = config.guardSuggestions ? "allowlist only" : "unrestricted";
        if (!config.enabled) {
            return Component.literal("Tab completion: " + state + " (inactive, guard off)");
        }
        return Component.literal("Tab completion: " + state);
    }

    /**
     * The exposure toggle's label, reflecting the state that is actually in force.
     *
     * <p>{@code config.enabled} gates the exposure layer too, so this button used to read
     * "Exposure whitelist: ON" while {@code /cmdguard off} had switched all filtering off --
     * the screen asserting a protection that was not running. The button still toggles
     * {@code exposure.enabled} (its own setting); only what it claims changed.
     */
    private static Component exposureLabel(GuardConfig config) {
        if (!config.exposure.enabled) {
            return Component.literal("Exposure whitelist: OFF");
        }
        return Component.literal(config.enabled
                ? "Exposure whitelist: ON"
                : "Exposure whitelist: ON (inactive, guard off)");
    }

    /** Likewise: inbound probes are only blocked while the exposure layer is actually running. */
    private static Component inboundLabel(GuardConfig config) {
        if (!config.exposureActive()) {
            return Component.literal("Inbound probes: "
                    + (config.exposure.filterInbound ? "blocked" : "allowed")
                    + " (inactive)");
        }
        return Component.literal("Inbound probes: "
                + (config.exposure.filterInbound ? "blocked" : "allowed"));
    }

    /**
     * The login-phase toggle. Worded as what happens rather than as a switch name, because
     * this is the one toggle whose "on" state can cost the player a join: a server whose
     * handshake genuinely needs a mod's answer refuses the connection, and the disconnect
     * screen comes from the server with nothing on it naming CmdGuard. The WARN line in
     * {@code latest.log} is where that is diagnosable, and it names the remedy --
     * {@code /cmdguard expose global <namespace>}, which is the <em>global</em> form because
     * the login phase runs before a connection has any server identity to key a grant on.
     */
    private static Component loginLabel(GuardConfig config) {
        String state = config.exposure.filterLogin ? "vanilla answer" : "mod may answer";
        if (!config.exposureActive()) {
            return Component.literal("Login queries: " + state + " (inactive)");
        }
        return Component.literal("Login queries: " + state);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        // Tied to the stack rather than to height/4, so it travels with it on a short window.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.stackTopY - 24, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
