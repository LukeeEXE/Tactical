package dev.tactical.paper.hud;

import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * How teams are presented. A team is NAMED after its current role (Attackers / Defenders) but always keeps the
 * COLOUR of its original identity for the whole match: team A (started attacking) red, team B blue.
 */
public final class TeamStyle {

    public static final TextColor RED = TextColor.color(0xB03232);
    public static final TextColor BLUE = TextColor.color(0x204EAB);

    private TeamStyle() {
    }

    public static TextColor color(TeamId team) {
        return team == TeamId.A ? RED : BLUE;
    }

    public static String roleName(Side side) {
        return side == Side.ATTACKERS ? "Attackers" : "Defenders";
    }

    /** "Attackers" / "Defenders" in the team's identity colour, for this moment of the match. */
    public static Component name(MatchFlow flow, TeamId team) {
        return Component.text(roleName(flow.sideOf(team)), color(team));
    }
}
