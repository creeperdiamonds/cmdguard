package studios.creeperdiamonds.cmdguard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persisted settings. Deliberately tiny: a flag, a click policy, and the allowlist.
 */
public final class GuardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("cmdguard.json");

    /**
     * Starter allowlist. A pure empty default-deny locks you out of auth servers --
     * /login and /register would be blocked and you would sit there unable to
     * authenticate -- so these ship enabled. Run "/cmdguard clear" for strict mode.
     */
    public static final List<String> STARTER_ALLOWLIST = List.of(
            "login", "register", "l", "reg", "2fa",
            "msg", "tell", "w", "r", "reply", "pm",
            "help", "list", "rules", "discord", "spawn",
            "home", "sethome", "tpa", "tpaccept", "tpdeny", "warp", "kit"
    );

    public boolean enabled = true;

    /**
     * Commands fired by clicking something the server rendered (plugin menus) arrive
     * through sendUnattendedCommand. You did not type them, and blocking them silently
     * breaks server GUIs, so they are permitted by default.
     */
    public boolean allowClickedCommands = true;

    public Set<String> allowlist = new LinkedHashSet<>(STARTER_ALLOWLIST);

    private static GuardConfig instance;

    public static GuardConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static GuardConfig load() {
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
                GuardConfig loaded = GSON.fromJson(reader, GuardConfig.class);
                if (loaded != null) {
                    if (loaded.allowlist == null) {
                        loaded.allowlist = new LinkedHashSet<>();
                    }
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                CmdGuardClient.LOGGER.warn("[cmdguard] could not read config, using defaults", e);
            }
        }
        GuardConfig fresh = new GuardConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] could not save config", e);
        }
    }

    public boolean allow(String root) {
        boolean changed = allowlist.add(root.toLowerCase(Locale.ROOT));
        if (changed) {
            save();
        }
        return changed;
    }

    public boolean deny(String root) {
        boolean changed = allowlist.remove(root.toLowerCase(Locale.ROOT));
        if (changed) {
            save();
        }
        return changed;
    }
}
