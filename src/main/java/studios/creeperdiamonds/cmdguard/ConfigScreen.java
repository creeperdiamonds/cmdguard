package studios.creeperdiamonds.cmdguard;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minimal settings screen reached from Mod Menu. The command allowlist is edited via
 * /cmdguard allow|deny and the exposure whitelist's namespaces via /cmdguard
 * expose|withhold, so this only surfaces the four toggles (guard, clicked-command
 * policy, exposure whitelist, inbound-probe filtering) plus a channel audit button.
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

        this.addRenderableWidget(Button.builder(
                Component.literal("Guard: " + (config.enabled ? "ON" : "OFF")),
                button -> {
                    config.enabled = !config.enabled;
                    config.save();
                    button.setMessage(Component.literal("Guard: " + (config.enabled ? "ON" : "OFF")));
                }).bounds(centerX - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Clicked commands: " + (config.allowClickedCommands ? "allowed" : "blocked")),
                button -> {
                    config.allowClickedCommands = !config.allowClickedCommands;
                    config.save();
                    button.setMessage(Component.literal("Clicked commands: "
                            + (config.allowClickedCommands ? "allowed" : "blocked")));
                }).bounds(centerX - 100, y + 24, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Audit mod channels (chat)"),
                button -> {
                    ChannelAudit.report();
                    this.onClose();
                }).bounds(centerX - 100, y + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Exposure whitelist: " + (config.exposure.enabled ? "ON" : "OFF")),
                button -> {
                    config.exposure.enabled = !config.exposure.enabled;
                    config.save();
                    button.setMessage(Component.literal("Exposure whitelist: "
                            + (config.exposure.enabled ? "ON" : "OFF")));
                }).bounds(centerX - 100, y + 72, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Inbound probes: "
                        + (config.exposure.filterInbound ? "blocked" : "allowed")),
                button -> {
                    config.exposure.filterInbound = !config.exposure.filterInbound;
                    config.save();
                    button.setMessage(Component.literal("Inbound probes: "
                            + (config.exposure.filterInbound ? "blocked" : "allowed")));
                }).bounds(centerX - 100, y + 96, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> this.onClose()).bounds(centerX - 100, y + 132, 200, 20).build());
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
