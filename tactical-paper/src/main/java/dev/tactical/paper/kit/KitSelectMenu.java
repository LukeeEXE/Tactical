package dev.tactical.paper.kit;

import dev.tactical.engine.kit.KitSelection;
import dev.tactical.engine.kit.KitSelectionPhase;
import dev.tactical.engine.kit.KitSelectionResult;
import dev.tactical.engine.match.MatchFlow;
import dev.tactical.engine.pack.PackBuilder;
import io.papermc.paper.datacomponent.DataComponentTypes;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kit selection (match start, halftime, sudden death), drawn like the shop: the whole screen is the title (header,
 * body and footer glyphs of {@code tactical:gui_shop}), the slots sit on its frames.
 * <ul>
 *   <li>red box (chest row 1, cols 2-6): the red team's picks; blue box (the viewer's top inventory row, cols 2-6):
 *       the blue team's picks (kits are public)</li>
 *   <li>rows 3-5: one category per row (Defense, Damage, Support): its icon in col 1, up to six kits in cols 2-7</li>
 *   <li>footer: skip vote in hotbar slot 2, the lock button (one item drawn over hotbar slots 4-6)</li>
 * </ul>
 * The viewer's inventory is put aside while the menu is open (it shows the blue team and the buttons) and given back
 * when it closes. Left-click a kit to select it (click again to lock), right-click to lock directly.
 */
final class KitSelectMenu extends Menu {

    private static final int[] RED_SLOTS = {11, 12, 13, 14, 15};
    /** Player-inventory slots of the blue box (top inventory row, cols 2-6). */
    private static final int[] BLUE_SLOTS = {11, 12, 13, 14, 15};
    private static final int[] CATEGORY_SLOTS = {28, 37, 46};
    private static final int KITS_PER_ROW = 6;
    /**
     * Clickable hotbar slots under the footer: skip vote (the square) and the lock button (slots 4-6). The button items
     * themselves sit in the 3rd inventory row, hidden under the footer, and are drawn 22 px lower onto these slots, so
     * the in-game hotbar (visible behind the screen) never shows them.
     */
    static final int SKIP_SLOT = 2, LOCK_LEFT = 4, LOCK_RIGHT = 6;
    static final int SKIP_ITEM_SLOT = 29, LOCK_ITEM_SLOT = 32;

    private final GameMatch match;
    private final ConfigService config;
    private final Icons icons;
    /** The viewer's inventory (0-35) while the menu is open. */
    private ItemStack[] stash;

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
        net.kyori.adventure.key.Key font = net.kyori.adventure.key.Key.key("tactical", "gui_shop");
        java.util.function.Function<Character, Component> glyph = c -> Component.text(String.valueOf(c)).font(font);
        return Component.text()
                .append(dev.tactical.paper.hud.TextOffsets.space(-8))                         // x 0
                .append(glyph.apply(PackBuilder.GUI_KIT_HEADER))                               // 176 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(-177 + 6))                   // x 6
                .append(glyph.apply(PackBuilder.GUI_KIT_BODY))                                 // 164 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(-165 - 6))                   // x 0
                .append(glyph.apply(PackBuilder.GUI_KIT_FOOTER))                               // 176 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(-177 + 7))                   // x 7: icon box
                .append(glyph.apply(PackBuilder.GUI_KIT_ICON))                                 // 16 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(37 - 7 - 17))                // x 37: 3 px into the bar
                .append(Component.text(match.flow().kitSelection().map(k -> phaseName(k.phase())).orElse("KITS"), TextColor.color(0xE4E8FA)).font(net.kyori.adventure.key.Key.key("tactical", "pinch_up_14")))
                .build()
                .color(NamedTextColor.WHITE)
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.none());
    }

    @Override
    public void onOpen(Player viewer) {
        PlayerInventory inv = viewer.getInventory();
        stash = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            stash[i] = inv.getItem(i);
            inv.setItem(i, null);
        }
        renderInventory(viewer);
    }

    @Override
    public void onClose(Player viewer) {
        if (stash == null) return;
        PlayerInventory inv = viewer.getInventory();
        for (int i = 0; i < 36; i++) if (isDisplay(inv.getItem(i))) inv.setItem(i, null); // heads and buttons go away
        if (isDisplay(viewer.getItemOnCursor())) viewer.setItemOnCursor(null);
        // Anything given while the menu was open (e.g. a base kit) stays; the put-aside items fill around it.
        for (int i = 0; i < 36; i++) {
            ItemStack it = stash[i];
            if (it == null || it.getType().isAir()) continue;
            ItemStack cur = inv.getItem(i);
            if (cur == null || cur.getType().isAir()) inv.setItem(i, it);
            else inv.addItem(it).values().forEach(left -> viewer.getWorld().dropItem(viewer.getLocation(), left));
        }
        stash = null;
    }

    @Override
    public boolean onPlayerSlotClick(Player viewer, int slot, ClickType click) {
        MatchFlow flow = match.flow();
        UUID me = viewer.getUniqueId();
        if ((slot == SKIP_SLOT || slot == SKIP_ITEM_SLOT) && flow.skipVoteAvailable()) {
            if (flow.voteSkip(me)) viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            return true;
        }
        if ((slot >= LOCK_LEFT && slot <= LOCK_RIGHT) || slot == LOCK_ITEM_SLOT) {
            Optional<KitSelection> sel = flow.kitSelection();
            if (sel.isEmpty() || sel.get().lockedBy(me).isPresent()) return true;
            String hovered = sel.get().hoveredBy(me).orElse(null);
            if (hovered == null) {
                feedbackText(viewer, "Select a kit first");
                return true;
            }
            feedback(viewer, flow.lockKit(me, hovered), true);
            return true;
        }
        return false;
    }

    @Override
    public void render(Player viewer, MenuView view) {
        MatchFlow flow = match.flow();
        Optional<KitSelection> selOpt = flow.kitSelection();
        if (selOpt.isEmpty()) return;
        KitSelection sel = selOpt.get();
        UUID me = viewer.getUniqueId();
        TeamId myTeam = match.member(me).map(MatchMember::team).orElse(TeamId.A);

        renderTeam(view, sel, TeamId.A, RED_SLOTS, NamedTextColor.RED);
        if (stash != null) renderInventory(viewer);

        String hovered = sel.hoveredBy(me).orElse(null);
        String locked = sel.lockedBy(me).orElse(null);
        KitDefinition.Category[] cats = KitDefinition.Category.values();
        for (int row = 0; row < cats.length; row++) {
            KitDefinition.Category cat = cats[row];
            view.set(CATEGORY_SLOTS[row], categoryIcon(cat));
            int col = 0;
            for (String kitId : flow.kitPool()) {
                KitDefinition def = config.kits().get(kitId);
                if (def == null || def.category() != cat) continue;
                if (col >= KITS_PER_ROW) break;
                int slot = CATEGORY_SLOTS[row] + 1 + col++;
                List<Component> lore = new ArrayList<>();
                lore.add(dev.tactical.paper.item.ItemLore.tag(cat.displayName().toUpperCase(java.util.Locale.ROOT),
                        TextColor.color(cat.color())));
                lore.add(Component.empty());
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
                    lore.add(Component.text(locked == null ? "Selected - click again or press LOCK IN" : "Selected", NamedTextColor.YELLOW));
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
                view.set(slot, icons.kit(def, state, Component.text(def.name(), nameColor).decoration(
                        net.kyori.adventure.text.format.TextDecoration.ITALIC, false), lore.stream().map(c -> c.decoration(
                        net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList(), glow), click -> {
                    if (locked != null) return;
                    if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT || id.equals(sel.hoveredBy(me).orElse(null))) {
                        feedback(viewer, flow.lockKit(me, id), true);
                    } else {
                        feedback(viewer, flow.hoverKit(me, id), false);
                    }
                });
            }
        }
    }

    /** The parts of the screen that live in the viewer's own inventory: the blue box, skip and lock buttons. */
    private void renderInventory(Player viewer) {
        Optional<KitSelection> selOpt = match.flow().kitSelection();
        if (selOpt.isEmpty()) return;
        KitSelection sel = selOpt.get();
        UUID me = viewer.getUniqueId();
        PlayerInventory inv = viewer.getInventory();
        ItemStack[] blue = teamItems(sel, TeamId.B, NamedTextColor.BLUE, BLUE_SLOTS.length);
        for (int i = 0; i < BLUE_SLOTS.length; i++) setIfChanged(inv, BLUE_SLOTS[i], blue[i]);
        MatchFlow flow = match.flow();
        ItemStack skip = null;
        if (flow.skipVoteAvailable()) {
            skip = new ItemStack(Material.PAPER);
            skip.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("tactical",
                    flow.hasVotedSkip(me) ? "kit_skip_pressed" : "kit_skip"));
            skip.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                    io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        }
        setIfChanged(inv, SKIP_ITEM_SLOT, skip);
        ItemStack lock = new ItemStack(Material.PAPER);
        lock.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(
                sel.lockedBy(me).isPresent() ? PackBuilder.MODEL_KIT_LOCKED : PackBuilder.MODEL_KIT_LOCK_IN));
        lock.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        setIfChanged(inv, LOCK_ITEM_SLOT, lock);
    }

    private static final org.bukkit.NamespacedKey DISPLAY_KEY = new org.bukkit.NamespacedKey("tactical", "menu_display");

    private static boolean isDisplay(ItemStack item) {
        return item != null && !item.getType().isAir()
                && item.getPersistentDataContainer().has(DISPLAY_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    private static void setIfChanged(PlayerInventory inv, int slot, ItemStack item) {
        if (item != null) item.editPersistentDataContainer(pdc -> pdc.set(DISPLAY_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        ItemStack cur = inv.getItem(slot);
        boolean curEmpty = cur == null || cur.getType().isAir();
        if (item == null ? curEmpty : item.equals(cur)) return;
        inv.setItem(slot, item);
    }

    private ItemStack categoryIcon(KitDefinition.Category cat) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("tactical", "kit_" + cat.id()));
        item.editMeta(m -> m.displayName(Component.text(cat.displayName(), NamedTextColor.WHITE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        return item;
    }

    private void renderTeam(MenuView view, KitSelection sel, TeamId team, int[] slots, NamedTextColor color) {
        ItemStack[] items = teamItems(sel, team, color, slots.length);
        for (int i = 0; i < slots.length; i++) if (items[i] != null) view.set(slots[i], items[i]);
    }

    /** Heads of the team's players with what they picked (null where the team has fewer players). */
    private ItemStack[] teamItems(KitSelection sel, TeamId team, NamedTextColor color, int max) {
        ItemStack[] out = new ItemStack[max];
        int i = 0;
        for (MatchMember m : match.members()) {
            if (m.team() != team || i >= max) continue;
            List<Component> lore = new ArrayList<>();
            String l = sel.lockedBy(m.uuid()).orElse(null);
            String h = sel.hoveredBy(m.uuid()).orElse(null);
            if (l != null) lore.add(Component.text("LOCKED: " + kitName(l), NamedTextColor.GREEN));
            else if (h != null) lore.add(Component.text("Selecting: " + kitName(h), NamedTextColor.YELLOW));
            else lore.add(Component.text("Choosing...", NamedTextColor.GRAY));
            if (m.virtual()) lore.add(Component.text("(virtual test player)", NamedTextColor.DARK_GRAY));
            // Once a player picks a kit, their slot shows that kit's logo instead of their head.
            String pick = l != null ? l : h;
            KitDefinition picked = pick == null ? null : config.kits().get(pick);
            out[i++] = picked != null
                    ? icons.kit(picked, "available", Component.text(m.name(), color), lore, false)
                    : icons.head(m.uuid(), m.name(), !m.virtual(), Component.text(m.name(), color), lore);
        }
        return out;
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

    private static void feedbackText(Player p, String text) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
        dev.tactical.paper.hud.ActionBars.status(p, Component.text(text, NamedTextColor.RED));
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
            case MATCH_START -> "PICK YOUR KIT";
            case SECOND_HALF -> "HALFTIME KIT";
            case SUDDEN_DEATH -> "SUDDEN DEATH KIT";
        };
    }
}
