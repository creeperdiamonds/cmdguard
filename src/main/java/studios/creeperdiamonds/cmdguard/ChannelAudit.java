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
 * Transparency, not concealment.
 *
 * <p>A server cannot read your mods folder and Fabric Loader never sends a mod list.
 * What a server CAN do is ask on a custom channel -- and a mod you installed may answer,
 * because FabricLoader#getAllMods is public API. This names the mods on your machine
 * capable of receiving such a request, so the choice to keep or remove them is yours.
 *
 * <p>It deliberately does not suppress any reply. Silently withholding an answer while
 * staying connected deceives the operator; removing the mod, or not joining, does not.
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
