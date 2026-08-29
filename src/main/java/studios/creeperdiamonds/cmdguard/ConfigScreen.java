package studios.creeperdiamonds.cmdguard;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minimal settings screen reached from Mod Menu. The command allowlist is edited via
 * /cmdguard allow|deny and the exposure whitelist's namespaces via /cmdguard
 * expose|withhold, so this only surfaces the five toggles (guard, clicked-command
 * policy, exposure whitelist, inbound-probe filtering, login-query filtering) plus a
 * channel audit button.
 *
 * <p>Every toggle rebuilds the whole screen rather than relabelling its own button, because
 * the guard's master switch also gates the exposure layer: the two exposure labels depend on
 * a setting a different button owns.
 */
public final class ConfigScreen extends Screen {
    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.literal("CmdGuard"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        GuardConfig config = GuardConfig.get();
        int centerX = this.width / 2;
        int y = this.height / 4;

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
                Component.literal("Audit mod channels (chat)"),
                button -> {
                    ChannelAudit.report();
                    this.onClose();
                }).bounds(centerX - 100, y + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                exposureLabel(config),
                button -> {
                    config.exposure.enabled = !config.exposure.enabled;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 72, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                inboundLabel(config),
                button -> {
                    config.exposure.filterInbound = !config.exposure.filterInbound;
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 96, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                loginLabel(config),
                button -> {
                    config.exposure.filterLogin = !config.exposure.loginFilterEnabled();
                    config.save();
                    this.rebuildWidgets();
                }).bounds(centerX - 100, y + 120, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> this.onClose()).bounds(centerX - 100, y + 156, 200, 20).build());
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
        String state = config.exposure.loginFilterEnabled() ? "vanilla answer" : "mod may answer";
        if (!config.exposureActive()) {
            return Component.literal("Login queries: " + state + " (inactive)");
        }
        return Component.literal("Login queries: " + state);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 24, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
