package dev.tactical.paper.hud;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real player heads for text (boss bar, chat, later tab list and leaderboards) and menu items.
 * A head built from only a UUID/name renders as a default skin, because the client is not given the skin;
 * this attaches the player's signed {@code textures} property. Textures are cached when players are online,
 * so heads still render correctly after someone disconnects (e.g. in the post-match results).
 */
public final class Heads {

    private static final Map<UUID, List<ProfileProperty>> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SOURCE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKUP_STARTED = new ConcurrentHashMap<>();
    private static final long LOOKUP_RETRY_MS = 10 * 60 * 1000L;

    private Heads() {
    }

    /** Remembers a player's skin textures (call on join and whenever a head is built for an online player). */
    public static void remember(Player p) {
        List<ProfileProperty> t = new ArrayList<>();
        for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) t.add(prop);
        }
        if (!t.isEmpty()) {
            TEXTURES.put(p.getUniqueId(), List.copyOf(t));
            SOURCE.put(p.getUniqueId(), "login profile");
        }
    }

    /**
     * Skin textures for a player. Uses the login profile when it has a skin; otherwise (offline-mode servers,
     * proxies that do not forward skins) looks the skin up from Mojang by UUID, then by name, in the background.
     * Until that finishes the head shows the default skin; menus and the boss bar refresh on their own.
     */
    public static Collection<ProfileProperty> textures(UUID id, String name) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) remember(online);
        List<ProfileProperty> t = TEXTURES.get(id);
        if (t != null) return t;
        lookup(id, name);
        return List.of();
    }

    /** For diagnostics: where a player's skin came from, or why it is missing. */
    public static String describe(UUID id) {
        if (TEXTURES.containsKey(id)) return "skin from " + SOURCE.getOrDefault(id, "?");
        return LOOKUP_STARTED.containsKey(id) ? "no skin yet (Mojang lookup started/failed)" : "no skin";
    }

    private static void lookup(UUID id, String name) {
        long now = System.currentTimeMillis();
        Long started = LOOKUP_STARTED.get(id);
        if (started != null && now - started < LOOKUP_RETRY_MS) return;
        LOOKUP_STARTED.put(id, now);
        Bukkit.createProfile(id, name).update().thenAccept(profile -> {
            if (!store(id, profile, "Mojang (by UUID)") && name != null) {
                Bukkit.createProfile(name).update().thenAccept(byName -> store(id, byName, "Mojang (by name)"));
            }
        }).exceptionally(ex -> {
            if (name != null) Bukkit.createProfile(name).update().thenAccept(byName -> store(id, byName, "Mojang (by name)"));
            return null;
        });
    }

    private static boolean store(UUID id, com.destroystokyo.paper.profile.PlayerProfile profile, String source) {
        List<ProfileProperty> t = new ArrayList<>();
        for (ProfileProperty prop : profile.getProperties()) if (prop.getName().equals("textures")) t.add(prop);
        if (t.isEmpty()) return false;
        TEXTURES.put(id, List.copyOf(t));
        SOURCE.put(id, source);
        return true;
    }

    /** Inline head (object text component) with the real skin and hat layer. */
    public static Component text(UUID id, String name) {
        PlayerHeadObjectContents.Builder b = ObjectContents.playerHead().id(id).name(name).hat(true);
        for (ProfileProperty prop : textures(id, name)) {
            b.profileProperty(prop.getSignature() == null
                    ? PlayerHeadObjectContents.property(prop.getName(), prop.getValue())
                    : PlayerHeadObjectContents.property(prop.getName(), prop.getValue(), prop.getSignature()));
        }
        return Component.object(b.build());
    }
}
