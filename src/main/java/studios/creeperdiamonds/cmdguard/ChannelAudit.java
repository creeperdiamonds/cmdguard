package studios.creeperdiamonds.cmdguard;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;

/**
 * Enumerates which installed mods have registered to receive a networking channel --
 * i.e. which of your mods a server could ask something of -- and cross-references that
 * against the channels the connected server has declared it accepts. {@code /cmdguard
 * audit} is this class's report.
 *
 * <p>This class does not decide what is exposed or withheld, and prints no verdict.
 * That decision, and its per-channel readout, belongs to the exposure whitelist ({@code
 * ExposurePolicy}, backed by {@code ChannelLedger}) and is shown by {@code /cmdguard
 * exposure}, not here.
 *
 * <p>CmdGuard withholds what is not on the exposure whitelist and never fabricates. It
 * does not claim to be vanilla, does not alter minecraft:brand, and never advertises a
 * channel or identifier the client does not actually have. Declining to answer is a
 * refusal; inventing an answer would be a lie, and this mod does not do the second.
 */
public final class ChannelAudit {
    private ChannelAudit() {
    }

    public record Entry(String channel, String namespace, String modName) {
    }

    /** Channels some installed mod has registered a receiver for. */
    public static List<Entry> listeningMods() {
        Set<Identifier> channels = ClientPlayNetworking.getGlobalReceivers();
        Map<String, Entry> byChannel = new TreeMap<>();

        for (Identifier channel : channels) {
            String namespace = channel.getNamespace();
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(namespace);
            String modName = container
                    .map(mod -> mod.getMetadata().getName())
                    .orElse("unknown (" + namespace + ")");
            byChannel.put(channel.toString(), new Entry(channel.toString(), namespace, modName));
        }

        return new ArrayList<>(byChannel.values());
    }

    /** Channels the connected server has declared it accepts. */
    public static List<String> serverDeclaredChannels() {
        List<String> out = new ArrayList<>();
        for (Identifier id : ClientPlayNetworking.getSendable()) {
            out.add(id.toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    public static void report() {
        List<Entry> listening = listeningMods();

        if (listening.isEmpty()) {
            OutboundGuard.say(Component.literal("CmdGuard: no installed mod is listening on a server channel.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        OutboundGuard.say(Component.literal("CmdGuard: " + listening.size()
                        + " channel(s) your mods can be contacted on:")
                .withStyle(ChatFormatting.GOLD));

        for (Entry entry : listening) {
            OutboundGuard.say(Component.literal("  " + entry.channel())
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("  <- " + entry.modName()).withStyle(ChatFormatting.GRAY)));
        }
    }

    /** Called when a server announces channels it accepts. */
    public static void onServerChannels(List<Identifier> channels) {
        if (channels.isEmpty()) {
            return;
        }

        List<String> interesting = new ArrayList<>();
        Set<Identifier> mine = ClientPlayNetworking.getGlobalReceivers();

        for (Identifier channel : channels) {
            if (mine.contains(channel)) {
                interesting.add(channel.toString());
            }
        }

        if (!interesting.isEmpty()) {
            OutboundGuard.say(Component.literal(
                            "CmdGuard: this server accepts channel(s) one of your mods also handles: "
                                    + String.join(", ", interesting))
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
