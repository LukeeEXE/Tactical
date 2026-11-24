package dev.tactical.paper.hud;

import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;

/**
 * Chat line format: {@code [icon] message}, with players shown as {@code <team shield> <head> Name} in their team
 * colour. Icons are original pixel art from the resource pack ({@code tactical:hud_chat}); without the pack the
 * brackets simply hold nothing.
 */
public final class ChatStyle {

    private static final Key FONT = Key.key("tactical", "hud_chat");

    private ChatStyle() {
    }

    public static Component icon(char glyph) {
        return Component.text(String.valueOf(glyph)).font(FONT).color(NamedTextColor.WHITE);
    }

    /** {@code [icon] body} */
    public static Component line(char glyph, Component body) {
        return Component.text()
                .append(Component.text("[", NamedTextColor.WHITE)).append(icon(glyph)).append(Component.text("] ", NamedTextColor.WHITE))
                .append(body)
                .build();
    }

    /** Team shield, head and coloured name of a match member. */
    public static Component player(GameMatch match, UUID id) {
        MatchMember m = match.member(id).orElse(null);
        if (m == null) return Component.text("?", NamedTextColor.GRAY);
        Component shield = icon(m.team() == TeamId.A ? HudGlyphs.CHAT_SHIELD_RED : HudGlyphs.CHAT_SHIELD_BLUE);
        Component head = m.virtual() ? Component.text("●", TeamStyle.color(m.team())) : Heads.text(m.uuid(), m.name());
        return Component.text().append(shield).append(Component.text(" ")).append(head).append(Component.text(" "))
                .append(Component.text(m.name(), TeamStyle.color(m.team()))).build();
    }

    /** A team named by its current role, with its shield. */
    public static Component team(GameMatch match, TeamId team) {
        Component shield = icon(team == TeamId.A ? HudGlyphs.CHAT_SHIELD_RED : HudGlyphs.CHAT_SHIELD_BLUE);
        return Component.text().append(shield).append(Component.text(" ")).append(TeamStyle.name(match.flow(), team)).build();
    }
}
