package dev.tactical.paper.kit;

import dev.tactical.engine.kit.KitSelection;
import dev.tactical.engine.kit.KitSelectionPhase;
import dev.tactical.engine.kit.KitSelectionResult;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.team.TeamId;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.config.KitDefinition;
import dev.tactical.paper.gui.Icons;
import dev.tactical.paper.gui.Menu;
import dev.tactical.paper.gui.MenuView;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kit selection (match start, halftime, sudden death). Layout:
 * row 0 header, row 1 your team's picks, rows 2-3 the kits, row 4 the enemy team's picks (kits are public),
 * row 5 lock / skip buttons. Left-click a kit to select it (click again to lock), right-click to lock directly.
 */
final class KitSelectMenu extends Menu {

    private static final int[] TEAM_SLOTS = {11, 12, 13, 14, 15};
    private static final int[] ENEMY_SLOTS = {38, 39, 40, 41, 42};
    private static final int KIT_START = 18;

    private final GameMatch match;
    private final ConfigService config;
    private final Icons icons;

    KitSelectMenu(GameMatch match, ConfigService config, Icons icons) {
        this.match = match;
        this.config = config;
        this.icons = icons;
    }

    GameMatch match() {
        return match;
    }

    @Override
    public Component title(Player viewer) {
        return Component.text("Kit selection");
    }

    @Override
    public void render(Player viewer, MenuView view) {
        MatchFlow flow = match.flow();
        Optional<KitSelection> selOpt = flow.kitSelection();
        if (selOpt.isEmpty()) {
            view.set(22, icons.icon("ui.info", Component.text("Kit selection is closed", NamedTextColor.GRAY), List.of()));
            return;
        }
        KitSelection sel = selOpt.get();
        UUID me = viewer.getUniqueId();
        TeamId myTeam = match.member(me).map(MatchMember::team).orElse(TeamId.A);

        for (int i = 0; i < 9; i++) view.set(i, icons.icon("ui.filler", Component.empty(), List.of()));
        int secs = (flow.stateTicksRemaining() + ModeRules.TICKS_PER_SECOND - 1) / ModeRules.TICKS_PER_SECOND;
        view.set(4, icons.icon("ui.timer", Component.text(phaseName(sel.phase()) + " - " + secs + "s", NamedTextColor.GOLD),
                List.of(Component.text(phaseHint(sel.phase()), NamedTextColor.GRAY),
                        Component.text("Teammates cannot pick the same kit.", NamedTextColor.GRAY),
                        Component.text("Your team plays " + flow.sideOf(myTeam).name().toLowerCase(), NamedTextColor.YELLOW))));

        renderTeam(view, sel, myTeam, TEAM_SLOTS, NamedTextColor.GREEN);
        renderTeam(view, sel, myTeam.other(), ENEMY_SLOTS, NamedTextColor.RED);

        // kits
        int slot = KIT_START;
        String hovered = sel.hoveredBy(me).orElse(null);
        String locked = sel.lockedBy(me).orElse(null);
        for (String kitId : flow.kitPool()) {
            if (slot > 35) break;
            KitDefinition def = config.kits().get(kitId);
            if (def == null) continue;
            List<Component> lore = new ArrayList<>();
            if (!def.description().isEmpty()) lore.add(Component.text(def.description(), NamedTextColor.GRAY));
            String state;
            boolean glow = false;
            KitSelectionResult can = sel.canLock(me, kitId);
            String takenBy = teammateLocking(sel, myTeam, me, kitId);
            if (kitId.equals(locked)) {
                state = "locked";
                glow = true;
                lore.add(Component.text("LOCKED IN", NamedTextColor.GREEN));
            } else if (takenBy != null) {
                state = "taken";
                lore.add(Component.text("Locked by teammate " + takenBy, NamedTextColor.RED));
            } else if (!sel.optionsOf(me).contains(kitId)) {
                state = "unavailable";
                lore.add(Component.text(sel.phase() == KitSelectionPhase.SUDDEN_DEATH
                        ? "Sudden death: only your first- or second-half kit" : "Not available on this map", NamedTextColor.DARK_GRAY));
            } else if (can == KitSelectionResult.WOULD_STRAND_TEAMMATE) {
                state = "unavailable";
                lore.add(Component.text("A teammate needs this kit", NamedTextColor.DARK_GRAY));
            } else if (kitId.equals(hovered)) {
                state = "selected";
                glow = true;
                lore.add(Component.text(locked == null ? "Selected - click again to lock" : "Selected", NamedTextColor.YELLOW));
            } else {
                state = "available";
                if (locked == null) lore.add(Component.text("Click to select, right-click to lock", NamedTextColor.GRAY));
            }
            List<String> hovering = teammatesHovering(sel, myTeam, me, kitId);
            if (!hovering.isEmpty()) lore.add(Component.text("Selected by: " + String.join(", ", hovering), NamedTextColor.AQUA));
            NamedTextColor nameColor = switch (state) {
                case "taken", "unavailable" -> NamedTextColor.DARK_GRAY;
                case "locked" -> NamedTextColor.GREEN;
                case "selected" -> NamedTextColor.YELLOW;
                default -> NamedTextColor.WHITE;
            };
            final String id = kitId;
            view.set(slot++, icons.kit(def, state, Component.text(def.name(), nameColor), lore, glow), click -> {
                if (locked != null) return;
                if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT || id.equals(sel.hoveredBy(me).orElse(null))) {
                    feedback(viewer, flow.lockKit(me, id), true);
                } else {
                    feedback(viewer, flow.hoverKit(me, id), false);
                }
            });
        }

        // footer
        for (int i = 45; i < 54; i++) view.set(i, icons.icon("ui.filler", Component.empty(), List.of()));
        if (locked != null) {
            view.set(49, icons.icon("kit.lock_disabled", Component.text("Locked: " + kitName(locked), NamedTextColor.GREEN), List.of()));
        } else if (hovered != null && sel.canLock(me, hovered) == KitSelectionResult.OK) {
            view.set(49, icons.icon("kit.lock", Component.text("Lock in " + kitName(hovered), NamedTextColor.GREEN), List.of()),
                    c -> feedback(viewer, flow.lockKit(me, hovered), true));
        } else {
            view.set(49, icons.icon("kit.lock_disabled", Component.text("Select a kit first", NamedTextColor.GRAY), List.of()));
        }
        if (flow.skipVoteAvailable()) {
            boolean voted = flow.hasVotedSkip(me);
            List<Component> lore = List.of(
                    Component.text("Your team: " + (flow.teamAgreesToSkip(myTeam) ? "agrees" : "not yet"), NamedTextColor.GRAY),
                    Component.text("Enemy team: " + (flow.teamAgreesToSkip(myTeam.other()) ? "agrees" : "not yet"), NamedTextColor.GRAY),
                    Component.text("Starts early once everyone is locked and both teams agree.", NamedTextColor.DARK_GRAY));
            view.set(51, icons.icon(voted ? "kit.skip_voted" : "kit.skip",
                    Component.text(voted ? "You voted to skip" : "Vote to skip the break", NamedTextColor.AQUA), lore), c -> {
                if (flow.voteSkip(me)) viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            });
        }
    }

    private void renderTeam(MenuView view, KitSelection sel, TeamId team, int[] slots, NamedTextColor color) {
        int i = 0;
        for (MatchMember m : match.members()) {
            if (m.team() != team || i >= slots.length) continue;
            List<Component> lore = new ArrayList<>();
            String l = sel.lockedBy(m.uuid()).orElse(null);
            String h = sel.hoveredBy(m.uuid()).orElse(null);
            if (l != null) lore.add(Component.text("LOCKED: " + kitName(l), NamedTextColor.GREEN));
            else if (h != null) lore.add(Component.text("Selecting: " + kitName(h), NamedTextColor.YELLOW));
            else lore.add(Component.text("Choosing...", NamedTextColor.GRAY));
            if (m.virtual()) lore.add(Component.text("(virtual test player)", NamedTextColor.DARK_GRAY));
            view.set(slots[i++], icons.head(m.uuid(), m.name(), !m.virtual(), Component.text(m.name(), color), lore));
        }
    }

    private String teammateLocking(KitSelection sel, TeamId team, UUID me, String kit) {
        for (MatchMember m : match.members()) {
            if (m.team() == team && !m.uuid().equals(me) && kit.equals(sel.lockedBy(m.uuid()).orElse(null))) return m.name();
        }
        return null;
    }

    private List<String> teammatesHovering(KitSelection sel, TeamId team, UUID me, String kit) {
        List<String> out = new ArrayList<>();
        for (MatchMember m : match.members()) {
            if (m.team() == team && !m.uuid().equals(me) && sel.lockedBy(m.uuid()).isEmpty()
                    && kit.equals(sel.hoveredBy(m.uuid()).orElse(null))) out.add(m.name());
        }
        return out;
    }

    private String kitName(String id) {
        KitDefinition d = config.kits().get(id);
        return d == null ? id : d.name();
    }

    private static void feedback(Player p, KitSelectionResult r, boolean lock) {
        if (r == KitSelectionResult.OK) {
            p.playSound(p.getLocation(), lock ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.UI_BUTTON_CLICK, 1f, lock ? 1.6f : 1f);
            return;
        }
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
        dev.tactical.paper.hud.ActionBars.status(p, Component.text(switch (r) {
            case TAKEN_BY_TEAMMATE -> "A teammate already locked that kit";
            case WOULD_STRAND_TEAMMATE -> "A teammate has no other option - pick another kit";
            case KIT_NOT_AVAILABLE -> "That kit is not available to you";
            case ALREADY_LOCKED -> "You already locked your kit";
            case SELECTION_CLOSED -> "Kit selection is closed";
            default -> r.name();
        }, NamedTextColor.RED));
    }

    private static String phaseName(KitSelectionPhase p) {
        return switch (p) {
            case MATCH_START -> "Pick your kit";
            case SECOND_HALF -> "Halftime - second-half kit";
            case SUDDEN_DEATH -> "Sudden death kit";
        };
    }

    private static String phaseHint(KitSelectionPhase p) {
        return switch (p) {
            case MATCH_START -> "Locked kits are used for the first half.";
            case SECOND_HALF -> "Teams have switched sides.";
            case SUDDEN_DEATH -> "Choose your first- or second-half kit.";
        };
    }
}
