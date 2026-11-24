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

    private Heads() {
    }

    /** Remembers a player's skin textures (call on join and whenever a head is built for an online player). */
    public static void remember(Player p) {
        List<ProfileProperty> t = new ArrayList<>();
        for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) t.add(prop);
        }
        if (!t.isEmpty()) TEXTURES.put(p.getUniqueId(), List.copyOf(t));
    }

    public static Collection<ProfileProperty> textures(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) remember(online);
        return TEXTURES.getOrDefault(id, List.of());
    }

    /** Inline head (object text component) with the real skin and hat layer. */
    public static Component text(UUID id, String name) {
        PlayerHeadObjectContents.Builder b = ObjectContents.playerHead().id(id).name(name).hat(true);
        for (ProfileProperty prop : textures(id)) {
            b.profileProperty(prop.getSignature() == null
                    ? PlayerHeadObjectContents.property(prop.getName(), prop.getValue())
                    : PlayerHeadObjectContents.property(prop.getName(), prop.getValue(), prop.getSignature()));
        }
        return Component.object(b.build());
    }
}
