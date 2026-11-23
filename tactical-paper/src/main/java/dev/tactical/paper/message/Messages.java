package dev.tactical.paper.message;

import dev.tactical.paper.config.ConfigService;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;

/** Renders player-facing text from messages/en_US.yml. Placeholder VALUES are inserted as plain text (no injection). */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ConfigService config;

    public Messages(ConfigService config) {
        this.config = config;
    }

    /** @param placeholders alternating name, value pairs; values are escaped (unparsed) */
    public Component render(String key, Object... placeholders) {
        String template = config.messages().getOrDefault(key, key);
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            Object v = placeholders[i + 1];
            String name = String.valueOf(placeholders[i]);
            resolvers.add(v instanceof Component c ? Placeholder.component(name, c) : Placeholder.unparsed(name, String.valueOf(v)));
        }
        return MM.deserialize(template, TagResolver.resolver(resolvers));
    }

    /** Admin-authored MiniMessage (e.g. mode display names) — trusted input. */
    public Component mini(String text) {
        return MM.deserialize(text);
    }

    public Component prefixed(String key, Object... placeholders) {
        return MM.deserialize(config.messages().getOrDefault("prefix", "")).append(render(key, placeholders));
    }

    public void send(Audience to, String key, Object... placeholders) {
        to.sendMessage(prefixed(key, placeholders));
    }
}
